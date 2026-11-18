package com.kilon.agentvscreen.core

import org.junit.Assert.*
import org.junit.Test

class InspectorViewRegistryTest {
    @Test fun localViewingDoesNotNeedATurnOrAStopFlag() {
        val r=InspectorViewRegistry(); val ticket=r.begin("a",9,2,"idle-owner")
        assertTrue(r.valid(ticket,9,2,"idle-owner")); assertTrue(r.active(9,2,"idle-owner"))
    }
    @Test fun noAiOwnerIsAlsoSupportedForExistingLocalDisplay() {
        val r=InspectorViewRegistry(); val t=r.begin("a",9,2,null)
        assertTrue(r.valid(t,9,2,null)); assertFalse(r.valid(t,0,2,null))
    }
    @Test fun severalViewsAndIdempotentClose() {
        val r=InspectorViewRegistry();val a=r.begin("a",9,2,"x");val b=r.begin("b",9,2,"x")
        assertTrue(r.end(a));assertFalse(r.end(a));assertTrue(r.active(9,2,"x"));assertTrue(r.end(b));assertFalse(r.active(9,2,"x"))
    }
    @Test fun changedDisplayCannotBeProtectedOrReleasedByOldTicket() {
        val r=InspectorViewRegistry();val a=r.begin("a",9,2,"x")
        assertFalse(r.active(10,3,"x"));assertFalse(r.valid(a,10,3,"x"));assertFalse(r.end(a))
    }
    @Test fun changedOwnerRevokesOldView() {
        val r=InspectorViewRegistry();val a=r.begin("a",9,2,"x")
        assertFalse(r.active(9,2,"y"));assertFalse(r.valid(a,9,2,"y"))
    }
    @Test fun closingDisplayRevokesEveryTicket() {
        val r=InspectorViewRegistry();val a=r.begin("a",9,2,"x");r.clear()
        assertFalse(r.valid(a,9,2,"x"));assertFalse(r.active(null,null,null))
    }
    @Test(expected=IllegalArgumentException::class) fun defaultDisplayNeverAccepted() { InspectorViewRegistry().begin("a",0,1,null) }
    @Test(expected=IllegalArgumentException::class) fun duplicateTokenNeverAccepted() { val r=InspectorViewRegistry();r.begin("a",9,1,"x");r.begin("a",9,1,"x") }
}
