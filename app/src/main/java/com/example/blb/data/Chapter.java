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

    /** 2026-09-14 番外核对仍须区分未知与免费；未读到的价格不能写成零。 */
    @ColumnInfo(name = "price_coupons")
    public int priceCoupons = -1;

    /** 2026-09-14 无标号番外必须按卷名和完整标题消歧；旧目录未取证时保持 null。 */
    @ColumnInfo(name = "volume_title")
    public String volumeTitle;
}
