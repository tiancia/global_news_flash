package com.globalnewsflash.automation.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BudgetMapper {
    record BudgetPeriod(String id, long maxMicros, int maxRequests, long reservedMicros, long spentMicros, int usedRequests) {}
    record Reservation(String id, String periodId, long amountMicros, Long actualMicros, String state, long createdAt) {}

    @Select("SELECT * FROM budget_period WHERE id=#{id} FOR UPDATE") BudgetPeriod lockPeriod(String id);
    @Select("SELECT * FROM budget_period ORDER BY id") List<BudgetPeriod> periods();
    @Select("SELECT * FROM budget_reservation WHERE id=#{id}") Reservation find(String id);
    @Select("SELECT * FROM budget_reservation WHERE id=#{id} FOR UPDATE") Reservation lockReservation(String id);
    @Insert("INSERT INTO budget_period VALUES(#{id},#{micros},#{requests},0,0,0)")
    void create(@Param("id") String id, @Param("micros") long micros, @Param("requests") int requests);
    @Insert("INSERT INTO budget_reservation VALUES(#{id},#{periodId},#{amountMicros},NULL,'RESERVED',#{createdAt})")
    void insert(Reservation reservation);
    @Update("UPDATE budget_period SET reserved_micros=reserved_micros+#{amount},used_requests=used_requests+1 WHERE id=#{id}")
    void reserve(@Param("id") String id, @Param("amount") long amount);
    @Update("UPDATE budget_period SET reserved_micros=reserved_micros-#{reserved},spent_micros=spent_micros+#{actual},used_requests=used_requests-#{refundRequest} WHERE id=#{id}")
    void finish(@Param("id") String id, @Param("reserved") long reserved, @Param("actual") long actual,
                @Param("refundRequest") int refundRequest);
    @Update("UPDATE budget_reservation SET state=#{state},actual_micros=#{actual} WHERE id=#{id}")
    void state(@Param("id") String id, @Param("state") String state, @Param("actual") Long actual);
}
