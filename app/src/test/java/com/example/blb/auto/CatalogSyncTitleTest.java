package com.example.blb.auto;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CatalogSyncTitleTest {
    @Test
    public void aFormatChangeOrRenumberingKeepsTheSameChapter() {
        assertTrue(CatalogSync.sameChapter("83 最后", "第83章 最后"));
        assertTrue(CatalogSync.sameChapter("第83章 最后", "84 最后"));
        assertTrue(CatalogSync.sameChapter("第83章 最后", "第84章 最后"));
        assertTrue(CatalogSync.sameChapter("最后", "第83章 最后"));
    }

    @Test
    public void missingAndMistypedChapterMarkersUseTheSameTitleRule() {
        assertTrue(CatalogSync.sameChapter("68 投影", "第68 投影"));
        assertTrue(CatalogSync.sameChapter("第68 投影", "第68章 投影"));
        assertTrue(CatalogSync.sameChapter("30 红温", "第30张 红温"));
        assertTrue(CatalogSync.sameChapter("第30张 红温", "第30章 红温"));
    }

    @Test
    public void numbersInTheTitleArePreservedForBothScannedAndHandwrittenRows() {
        assertTrue(CatalogSync.sameChapter("100天后", "第83章100天后"));
        assertTrue(CatalogSync.sameChapter("83 100天后", "第83章100天后"));
        assertFalse(CatalogSync.sameChapter("83 100天后", "第83章200天后"));
        assertFalse(CatalogSync.sameChapter("100天后", "第83章200天后"));
    }

    @Test
    public void aChapterReferenceInTheTitleIsNotStrippedTwice() {
        assertTrue(CatalogSync.sameChapter("83 第2章的秘密", "第83章 第2章的秘密"));
        assertTrue(CatalogSync.sameChapter("第2章的秘密", "第83章 第2章的秘密"));
        assertFalse(CatalogSync.sameChapter("第83章 第2章的秘密", "第83章 第3章的秘密"));
    }

    @Test
    public void differentCompleteTitlesNeverMatchJustBecauseOneContainsTheOther() {
        assertFalse(CatalogSync.sameChapter("83 最后", "第83章 最后的约定"));
        assertFalse(CatalogSync.sameChapter("83 最后的约定", "第83章 最后"));
    }
}
