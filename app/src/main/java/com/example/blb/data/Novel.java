package com.example.blb.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 一本小说。sfNovelId 是菠萝包里的书籍 id，可留空（手动登记时不一定知道）。 */
@Entity(tableName = "novel", indices = {@Index(value = "sf_novel_id", unique = true)})
public class Novel {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "sf_novel_id")
    public String sfNovelId;

    @NonNull
    public String title = "";

    public String author;

    public String note;

    /** 是否是当前「集中订阅」的目标书。同一时间只应有一本为 true。 */
    @ColumnInfo(name = "is_target")
    public boolean isTarget;

    /**
     * 从第几章开始订阅。前面那些章你已经看过或不想买，自动订阅一律不碰，
     * 免得把券花在旧章上。默认 1 = 从头开始。
     */
    @ColumnInfo(name = "start_chapter_no")
    public int startChapterNo = 1;

    /** 至少是 1；库里存了 0 或负数（老数据）也当成从头开始。 */
    public int startFrom() {
        return Math.max(1, startChapterNo);
    }
}
