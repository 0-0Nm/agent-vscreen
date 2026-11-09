// tsbridge: the built-in Tailscale node of Agent-VScreen (1.1.6).
//
// It joins the user's tailnet as its own device (userspace tsnet: no VPN slot, no root, no Tailscale app) and forwards
// HTTP that tailnet peers send to <node>:PORT on to the app's local server at 127.0.0.1:PORT. Every forwarded request
// carries X-VScreen-Tailnet: <secret> (a random value the app gives us at start, so nobody on the phone can fake a
// tailnet request) and X-VScreen-Peer: <who> (the peer's Tailscale login and device, from WhoIs).
//
// The app runs it as a child process and talks over pipes:
//   stdout: one JSON object per line whenever the state changes ({"state":"Running","ip":"100.x", ...}).
//   stdin:  commands, one per line: "logout", "quit". EOF (the app died) ends this process too.
//
// Configuration comes from the environment so nothing secret shows up in `ps`:
//   TSB_DIR (state dir), TSB_HOSTNAME, TSB_PORT, TSB_TARGET (host:port), TSB_SECRET, TSB_AUTHKEY (optional),
//   TSB_CONTROL_URL (optional, e.g. a Headscale server), TSB_DNS (comma separated resolvers of the phone's network).
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

type status struct {
	State    string `json:"state"`
	AuthURL  string `json:"auth_url,omitempty"`
	IP       string `json:"ip,omitempty"`
	DNSName  string `json:"dns_name,omitempty"`
	Tailnet  string `json:"tailnet,omitempty"`
	User     string `json:"user,omitempty"`
	Peers    int    `json:"peers"`
	Online   int    `json:"online"`
	Error    string `json:"error,omitempty"`
	LastPeer string `json:"last_peer,omitempty"`
}

var (
	outMu    sync.Mutex
	last     string
	lastPeer string
	peerMu   sync.Mutex
)

func emit(s status) {
	peerMu.Lock()
	s.LastPeer = lastPeer
	peerMu.Unlock()
	b, _ := json.Marshal(s)
	outMu.Lock()
	defer outMu.Unlock()
	if string(b) == last {
		return
	}
	last = string(b)
	fmt.Fprintln(os.Stdout, string(b))
}

func env(k, def string) string {
	if v := strings.TrimSpace(os.Getenv(k)); v != "" {
		return v
	}
	return def
}

// Android has no /etc/resolv.conf: Go's own resolver would ask 127.0.0.1:53 and fail. Use the resolvers the app read
// from the phone's active network, then public ones.
func setupDNS() {
	var servers []string
	for _, s := range strings.Split(os.Getenv("TSB_DNS"), ",") {
		if s = strings.TrimSpace(s); s != "" {
			servers = append(servers, net.JoinHostPort(strings.Trim(s, "[]"), "53"))
		}
	}
	servers = append(servers, "223.5.5.5:53", "1.1.1.1:53", "8.8.8.8:53")
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			var err error
			for _, s := range servers {
				var c net.Conn
				if c, err = d.DialContext(ctx, network, s); err == nil {
					return c, nil
				}
			}
			return nil, err
		},
	}
}

// Apps targeting Android 11+ may not list interfaces through netlink; net.Interfaces can fail there. Fall back to one
// synthetic interface carrying the address the kernel would use for the internet (a UDP "connect" sends nothing).
func setupInterfaces() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := net.Interfaces()
		if err == nil && len(ifs) > 0 {
			out := make([]netmon.Interface, 0, len(ifs))
			for i := range ifs {
				out = append(out, netmon.Interface{Interface: &ifs[i]})
			}
			return out, nil
		}
		c, derr := net.Dial("udp4", "8.8.8.8:53")
		if derr != nil {
			return nil, errors.Join(err, derr)
		}
		ip := c.LocalAddr().(*net.UDPAddr).IP
		c.Close()
		iface := &net.Interface{Index: 1, MTU: 1500, Name: "wlan0", Flags: net.FlagUp | net.FlagRunning | net.FlagMulticast}
		return []netmon.Interface{{Interface: iface, AltAddrs: []net.Addr{&net.IPNet{IP: ip, Mask: net.CIDRMask(24, 32)}}}}, nil
	})
}

func main() {
	setupDNS()
	setupInterfaces()
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true") // no log upload to Tailscale

	port := env("TSB_PORT", "18765")
	target := env("TSB_TARGET", "127.0.0.1:"+port)
	secret := os.Getenv("TSB_SECRET")
	if secret == "" {
		fmt.Fprintln(os.Stderr, "TSB_SECRET missing")
		os.Exit(2)
	}
	srv := &tsnet.Server{
		Dir:        env("TSB_DIR", "./ts"),
		Hostname:   env("TSB_HOSTNAME", "agent-vscreen"),
		AuthKey:    os.Getenv("TSB_AUTHKEY"),
		ControlURL: os.Getenv("TSB_CONTROL_URL"),
		Logf:       func(string, ...any) {},
		UserLogf:   func(f string, a ...any) { fmt.Fprintf(os.Stderr, f+"\n", a...) },
	}
	defer srv.Close()

	emit(status{State: "Starting"})
	if err := srv.Start(); err != nil {
		emit(status{State: "Error", Error: err.Error()})
		os.Exit(1)
	}
	lc, err := srv.LocalClient()
	if err != nil {
		emit(status{State: "Error", Error: err.Error()})
		os.Exit(1)
	}

	ln, err := srv.Listen("tcp", ":"+port)
	if err != nil {
		emit(status{State: "Error", Error: "listen: " + err.Error()})
		os.Exit(1)
	}
	tu, _ := url.Parse("http://" + target)
	rp := httputil.NewSingleHostReverseProxy(tu)
	rp.FlushInterval = -1
	base := rp.Director
	rp.Director = func(r *http.Request) {
		host := r.Host
		base(r)
		r.Host = host // the app checks that the caller named this node
		r.Header.Del("X-VScreen-Tailnet")
		r.Header.Del("X-VScreen-Peer")
		r.Header.Set("X-VScreen-Tailnet", secret)
		who := r.RemoteAddr
		if wi, err := lc.WhoIs(r.Context(), r.RemoteAddr); err == nil && wi != nil {
			name := ""
			if wi.UserProfile != nil {
				name = wi.UserProfile.LoginName
			}
			if wi.Node != nil {
				name += " @ " + strings.TrimSuffix(wi.Node.ComputedName, ".")
			}
			if name != "" {
				who = name
			}
		}
		r.Header.Set("X-VScreen-Peer", who)
		peerMu.Lock()
		lastPeer = who
		peerMu.Unlock()
	}
	rp.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusBadGateway)
		fmt.Fprintf(w, `{"ok":false,"code":"APP_NOT_ANSWERING","error":"手机上的 Agent-VScreen 没有回应（%s）：打开一次 App"}`, strings.ReplaceAll(err.Error(), `"`, `'`))
	}
	go func() { _ = (&http.Server{Handler: rp, ReadHeaderTimeout: 30 * time.Second}).Serve(ln) }()

	// Commands from the app; EOF = the app is gone.
	quit := make(chan struct{})
	go func() {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			switch strings.TrimSpace(sc.Text()) {
			case "logout":
				ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
				_ = lc.Logout(ctx)
				cancel()
			case "quit":
				close(quit)
				return
			}
		}
		close(quit)
	}()

	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		st, err := lc.Status(ctx)
		cancel()
		if err != nil {
			emit(status{State: "Error", Error: err.Error()})
		} else {
			s := status{State: st.BackendState, AuthURL: st.AuthURL}
			if len(st.TailscaleIPs) > 0 {
				for _, ip := range st.TailscaleIPs {
					if ip.Is4() {
						s.IP = ip.String()
					}
				}
			}
			if st.Self != nil {
				s.DNSName = strings.TrimSuffix(st.Self.DNSName, ".")
				if u, ok := st.User[st.Self.UserID]; ok {
					s.User = u.LoginName
				}
			}
			if st.CurrentTailnet != nil {
				s.Tailnet = st.CurrentTailnet.Name
			}
			for _, p := range st.Peer {
				s.Peers++
				if p.Online {
					s.Online++
				}
			}
			emit(s)
		}
		select {
		case <-quit:
			return
		case <-tick.C:
		}
	}
}
