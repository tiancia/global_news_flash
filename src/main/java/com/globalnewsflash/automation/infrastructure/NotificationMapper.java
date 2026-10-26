package com.globalnewsflash.automation.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationMapper {
    record Notice(String id, String category, String subjectId, String reasonCode, String state,
                  int attempts, long nextAttemptAt, String lastError) {}
    @Insert("INSERT INTO ops_notification VALUES(#{id},#{category},#{subjectId},#{reasonCode},'PENDING',0,0,NULL)")
    void insert(Notice notice);
    @Select("SELECT * FROM ops_notification WHERE state IN ('PENDING','SENDING') AND next_attempt_at<=#{now} ORDER BY next_attempt_at,id LIMIT 10")
    List<Notice> due(long now);
    @Update("UPDATE ops_notification SET state='SENDING',attempts=attempts+1,next_attempt_at=#{until} WHERE id=#{id} AND state IN ('PENDING','SENDING') AND next_attempt_at<=#{now}")
    int claim(@Param("id") String id, @Param("until") long until, @Param("now") long now);
    @Update("UPDATE ops_notification SET state=#{state},next_attempt_at=#{next},last_error=#{error} WHERE id=#{id} AND attempts=#{attempts}")
    void finish(@Param("id") String id, @Param("attempts") int attempts, @Param("state") String state,
                @Param("next") long next, @Param("error") String error);
    @Select("SELECT * FROM ops_notification ORDER BY next_attempt_at DESC LIMIT 100") List<Notice> list();
}
