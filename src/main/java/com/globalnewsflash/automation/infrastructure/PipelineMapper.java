package com.globalnewsflash.automation.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PipelineMapper {
    record SourceRow(String id, String sourceId, boolean enabled, boolean approved, boolean synthetic,
                     String licenseVersion, String permittedProducers, String scopeDescription) {}
    record EventVersion(String id, String eventKey, int eventVersion, String fingerprint,
                        String editorialState, String decisionPayload, String policyVersion,
                        String publicationId, String materialsPayload, String policyPayload, String sourcesPayload,
                        String budgetReservationId, long createdAt) {}

    @Select("SELECT * FROM news_source ORDER BY id")
    List<SourceRow> sources();

    @Select("SELECT * FROM news_source ORDER BY id FOR UPDATE")
    List<SourceRow> lockSources();

    @Update("UPDATE news_source SET enabled=#{enabled} WHERE id=#{id}")
    int enableSource(@Param("id") String id, @Param("enabled") boolean enabled);

    @Insert("INSERT INTO news_event(event_key,latest_version) VALUES(#{key},0)")
    void insertEvent(String key);

    @Select("SELECT latest_version FROM news_event WHERE event_key=#{key} FOR UPDATE")
    Integer lockEvent(String key);

    @Update("UPDATE news_event SET latest_version=#{version} WHERE event_key=#{key}")
    void updateVersion(@Param("key") String key, @Param("version") int version);

    @Select("SELECT * FROM news_event_version WHERE event_key=#{key} AND fingerprint=#{fingerprint} FOR UPDATE")
    EventVersion findVersion(@Param("key") String key, @Param("fingerprint") String fingerprint);

    @Select("SELECT * FROM news_event_version WHERE event_key=#{key} AND event_version=#{version}")
    EventVersion findNumber(@Param("key") String key, @Param("version") int version);

    @Select("SELECT latest_version FROM news_event WHERE event_key=#{key}")
    Integer latestVersion(String key);

    @Select("SELECT COUNT(*) FROM publication_operation WHERE event_key=#{key}")
    int publicationCount(String key);

    @Insert("""
            INSERT INTO news_event_version(id,event_key,event_version,fingerprint,editorial_state,
                decision_payload,policy_version,publication_id,materials_payload,policy_payload,sources_payload,budget_reservation_id,created_at)
            VALUES(#{id},#{eventKey},#{eventVersion},#{fingerprint},#{editorialState},#{decisionPayload},
                #{policyVersion},#{publicationId},#{materialsPayload},#{policyPayload},#{sourcesPayload},#{budgetReservationId},#{createdAt})
            """)
    void insertVersion(EventVersion version);

    @Select("SELECT * FROM news_event_version ORDER BY created_at,event_key,event_version")
    List<EventVersion> versions();

    @Select("SELECT id FROM news_material WHERE id=#{id} FOR UPDATE")
    String findMaterial(String id);

    @Insert("""
            INSERT INTO news_material(id,source_id,event_key,fingerprint,payload,discovered_at,synthetic)
            VALUES(#{id},#{source},#{event},#{fingerprint},#{payload},#{discovered},#{synthetic})
            """)
    void insertMaterial(@Param("id") String id, @Param("source") String source,
                        @Param("event") String event, @Param("fingerprint") String fingerprint,
                        @Param("payload") String payload, @Param("discovered") long discovered,
                        @Param("synthetic") boolean synthetic);

    @Insert("INSERT INTO news_audit(id,action,subject_id,details,created_at) VALUES(#{id},#{action},#{subject},#{details},#{created})")
    void audit(@Param("id") String id, @Param("action") String action, @Param("subject") String subject,
               @Param("details") String details, @Param("created") long created);

    @Select("SELECT COUNT(*) FROM news_material")
    long materialCount();

    @Select("SELECT COUNT(*) FROM news_audit")
    long auditCount();
}
