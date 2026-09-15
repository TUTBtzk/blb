package com.example.blb.auto;

import org.junit.Test;

import static org.junit.Assert.*;

public class DetailViewportTest {
    @Test public void onlyTheVisibleDetailListIsSelected() throws Exception {
        FakeNode target = list();
        FakeNode root = screen().add(target, list().visible(false),
                FakeNode.node().scrollable(true).withClass("android.widget.HorizontalScrollView"));
        assertSame(target, StepRunner.detailViewport(root, OfflineRunner.bundled()));
    }

    @Test public void wrongPageAmbiguousListsAndInvalidBoundsCannotReceiveGestures() throws Exception {
        SelectorSet selectors = OfflineRunner.bundled();
        assertNull(StepRunner.detailViewport(FakeNode.node().add(list()), selectors));
        assertNull(StepRunner.detailViewport(screen().add(list(), list()), selectors));
        assertNull(StepRunner.detailViewport(screen().add(list().withBounds(0, 0, 0, 0)), selectors));
        assertNull(StepRunner.detailViewport(screen().add(list().enabled(false)), selectors));
        assertNull(StepRunner.detailViewport(screen().add(list().withClass("android.widget.HorizontalScrollView")), selectors));
    }

    private static FakeNode screen() {
        return FakeNode.node().add(FakeNode.text("订阅明细").withId("title_tv"));
    }

    private static FakeNode list() {
        return FakeNode.node().withId("baseListView").withClass("android.widget.ListView")
                .collection(15).withBounds(0, 100, 1080, 2300);
    }
}
