package com.kilon.agentvscreen.cmd

import org.junit.Assert.*
import org.junit.Test

class LayoutInspectorV2Test {
    @Test fun primaryNamesAreReadableAndPasswordNeverExposesText() {
        assertEquals("数字 7", LayoutInspectorPresentation.title(null,null,"app:id/digit_7"," "))
        assertEquals("计算表达式", LayoutInspectorPresentation.title("null",null,"app:id/formula"," "))
        assertEquals("科学计算器", LayoutInspectorPresentation.title(null,"Scientific calculator",null,"ImageButton"))
        assertEquals("受保护内容", LayoutInspectorPresentation.title("secret","hint",null,"EditText",true))
    }
    @Test fun rawCodesAreNotPrimaryStatusMessages() {
        assertEquals("线框模式：只看布局，不叠加旧截图",LayoutInspectorPresentation.status("CACHED_FRAME"))
        assertFalse(LayoutInspectorPresentation.status("SOME_INTERNAL_ERROR").contains("SOME_INTERNAL_ERROR"))
        assertEquals("输入框",LayoutInspectorPresentation.kind("EditText",false,true))
    }
    @Test fun referenceIncludesSnapshotAndUserFacingNumber() {
        assertEquals("L-1234ABCD / 19号",LayoutInspectorPresentation.shortReference("L-1234ABCD",19))
    }
    @Test fun viewportAndHitTestingShareTheSameTransform() {
        val t=LayoutInspectorViewport.fit(500f,800f,1000,2000,1f,0f,0f)!!
        val source=t.source(t.left+250*t.scale,t.top+400*t.scale)!!
        assertEquals(250f,source.first,0.001f);assertEquals(400f,source.second,0.001f)
        val boxes=listOf(LayoutInspectorPolicy.Box(1,null,0,200,300,300,500,true,"button"))
        assertEquals(1,LayoutInspectorPolicy.hit(boxes,source.first,source.second))
    }
    @Test fun zoomAndPanRemainInSourceCoordinates() {
        val t=LayoutInspectorViewport.fit(500f,800f,1000,2000,3f,40f,-20f)!!
        val source=t.source(t.left+800*t.scale,t.top+1200*t.scale)!!
        assertEquals(800f,source.first,0.001f);assertEquals(1200f,source.second,0.001f)
    }
    @Test fun marginInvalidAndNonfinitePointsDoNotSelect() {
        val t=LayoutInspectorViewport.fit(500f,800f,1000,2000,1f,0f,0f)!!
        assertNull(t.source(t.left-1,t.top));assertNull(t.source(Float.NaN,5f))
        assertNull(LayoutInspectorViewport.fit(0f,0f,1000,2000,1f,0f,0f))
        assertNull(LayoutInspectorViewport.fit(500f,800f,0,2000,1f,0f,0f))
    }
}
