package com.globalnewsflash.automation.infrastructure;

import com.globalnewsflash.automation.domain.Alert;
import com.globalnewsflash.automation.domain.EditorJob;
import com.globalnewsflash.automation.domain.Run;
import com.globalnewsflash.automation.port.out.LiveAutomationRepository;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LiveAutomationMapper extends LiveAutomationRepository {

    @Select("SELECT * FROM live_editor_job WHERE event_key=#{key} AND fingerprint=#{fingerprint} FOR UPDATE")
    EditorJob lockJob(@Param("key") String key, @Param("fingerprint") String fingerprint);
    @Select("SELECT * FROM live_editor_job WHERE id=#{id} FOR UPDATE") EditorJob lockJobId(String id);
    @Insert("INSERT INTO live_editor_job VALUES(#{id},#{eventKey},#{eventVersion},#{fingerprint},#{state},#{reservationId},#{leaseUntil},#{createdAt},#{updatedAt},#{lastError})")
    void insertJob(EditorJob job);
    @Update("UPDATE live_editor_job SET state=#{state},last_error=#{error},updated_at=#{now} WHERE id=#{id}")
    void finishJob(@Param("id") String id, @Param("state") String state, @Param("error") String error, @Param("now") long now);
    @Update("UPDATE live_editor_job SET state='EDITING',reservation_id=#{reservation},lease_until=#{until},updated_at=#{now},last_error=NULL WHERE id=#{id} AND state='RETRYABLE'")
    int restartJob(@Param("id") String id, @Param("reservation") String reservation,
                   @Param("until") long until, @Param("now") long now);
    @Update("UPDATE news_event_version SET editorial_state='EDITING',decision_payload=#{decision},budget_reservation_id=#{reservation} WHERE event_key=#{key} AND event_version=#{version}")
    void restartVersion(@Param("key") String key, @Param("version") int version,
                        @Param("decision") String decision, @Param("reservation") String reservation);
    @Select("SELECT * FROM live_editor_job WHERE state='EDITING' AND lease_until<=#{now}")
    List<EditorJob> expiredJobs(long now);
    @Select("SELECT * FROM live_editor_job ORDER BY created_at DESC LIMIT 100") List<EditorJob> jobs();

    @Update("UPDATE news_event_version SET editorial_state=#{state},decision_payload=#{decision},publication_id=#{publicationId} WHERE event_key=#{key} AND event_version=#{version}")
    void finishVersion(@Param("key") String key, @Param("version") int version, @Param("state") String state,
                       @Param("decision") String decision, @Param("publicationId") String publicationId);

    @Update("UPDATE automation_lease SET owner=#{owner},lease_until=#{until} WHERE id='live-cycle' AND lease_until<=#{now}")
    int claimCycle(@Param("owner") String owner, @Param("until") long until, @Param("now") long now);
    @Update("UPDATE automation_lease SET lease_until=#{until} WHERE id='live-cycle' AND owner=#{owner} AND lease_until>#{now}")
    int renewCycle(@Param("owner") String owner, @Param("until") long until, @Param("now") long now);
    @Update("UPDATE automation_lease SET owner=NULL,lease_until=0 WHERE id='live-cycle' AND owner=#{owner}")
    int releaseCycle(String owner);
    @Insert("INSERT INTO automation_run VALUES(#{id},#{now},NULL,'RUNNING',0,NULL)")
    void startRun(@Param("id") String id, @Param("now") long now);
    @Update("UPDATE automation_run SET finished_at=#{now},state=#{state},processed=#{processed},last_error=#{error} WHERE id=#{id}")
    void finishRun(@Param("id") String id, @Param("now") long now, @Param("state") String state,
                   @Param("processed") int processed, @Param("error") String error);
    @Select("SELECT * FROM automation_run ORDER BY started_at DESC LIMIT 30") List<Run> runs();

    @Insert("INSERT INTO automation_alert VALUES(#{id},#{category},#{subjectId},#{reasonCode},#{createdAt})")
    void insertAlert(Alert alert);
    @Select("SELECT * FROM automation_alert ORDER BY created_at DESC LIMIT 100") List<Alert> alerts();
    @Select("SELECT a.* FROM automation_alert a WHERE NOT EXISTS (SELECT 1 FROM ops_notification n WHERE n.id=a.id) ORDER BY a.created_at,a.id LIMIT 100")
    List<Alert> unnotifiedAlerts();
}
