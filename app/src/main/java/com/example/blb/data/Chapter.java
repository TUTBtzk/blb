package com.example.blb.data;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 某本小说下的一章。chapterNo 是本地序号（1 起），用来排序和定位「下一章」。 */
@Entity(tableName = "chapter",
        foreignKeys = @ForeignKey(entity = Novel.class,
                parentColumns = "id",
                childColumns = "novel_id",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index(value = {"novel_id", "chapter_no"}, unique = true)})
public class Chapter {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "novel_id")
    public long novelId;

    @ColumnInfo(name = "chapter_no")
    public int chapterNo;

    public String title;

    @ColumnInfo(name = "sf_chapter_id")
    public String sfChapterId;

    /** 订阅这一章要花的火券，未知记 0。 */
    @ColumnInfo(name = "price_coupons")
    public int priceCoupons;
}
