package com.renewflow.eip.leave;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 業務單指向有效內容；只在共同組織鎖內切換，JDBC 讀取避免狀態快取。 */
@Repository
class LeaveDocumentRepository {
    record Document(Long id, Long employeeId, Long effectiveRequestId, long effectiveVersion, boolean canceled) {}
    private final JdbcTemplate jdbc;
    LeaveDocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    Document find(Long id) { return findAll(List.of(id)).get(id); }
    Map<Long, Document> findAll(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbc.query("select * from leave_document where id in (" + placeholders + ")", (rs, row) ->
            new Document(rs.getLong("id"), rs.getLong("employee_id"), (Long) rs.getObject("effective_request_id"),
                rs.getLong("effective_version"), rs.getBoolean("canceled")), ids.toArray()).stream()
            .collect(Collectors.toMap(Document::id, Function.identity()));
    }
    int activate(Long id, long expectedVersion, Long expectedId, Long nextId, boolean canceled) {
        return jdbc.update("update leave_document set effective_request_id=?, effective_version=effective_version+1, canceled=? " +
            "where id=? and effective_version=? and effective_request_id is not distinct from ?", nextId, canceled, id, expectedVersion, expectedId);
    }
}
