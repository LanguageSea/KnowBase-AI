package com.knowbase.qa;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.knowbase.doc.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps AI-service retrieval coordinates to database chunk ids and enforces the
 * citation integrity boundary: only chunks returned by this retrieval and
 * belonging to the authorised knowledge-base set may be persisted.
 */
@Component
public class QaCitationResolver {

    private static final Logger log = LoggerFactory.getLogger(QaCitationResolver.class);

    private final DocumentRepository documentRepository;

    public QaCitationResolver(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public List<QaDtos.Citation> resolve(
            List<Map<String, Object>> payloads,
            Set<Long> allowedKbIds,
            Set<String> retrievedKeys) {
        List<QaDtos.Citation> citations = new ArrayList<>();
        Set<Long> seenChunks = new HashSet<>();
        for (Map<String, Object> payload : payloads) {
            Long docId = longValue(payload.get("doc_id"));
            Long kbId = longValue(payload.get("kb_id"));
            Integer seq = intValue(payload.get("seq"));
            if (docId == null || kbId == null || seq == null) {
                continue;
            }
            String key = key(docId, seq);
            if (!allowedKbIds.contains(kbId) || !retrievedKeys.contains(key)) {
                log.warn("拒绝越界引用 docId={} kbId={} seq={}", docId, kbId, seq);
                continue;
            }

            DocumentRepository.DocumentRow document = documentRepository.findById(docId).orElse(null);
            if (document == null
                    || !"ACTIVE".equals(document.status())
                    || document.kbId() != kbId) {
                log.warn("拒绝失效或跨库引用 docId={} kbId={}", docId, kbId);
                continue;
            }
            Long chunkId = documentRepository.findChunkId(docId, seq);
            if (chunkId == null || !seenChunks.add(chunkId)) {
                continue;
            }
            String content = payload.get("quote") == null ? "" : String.valueOf(payload.get("quote"));
            Double score = doubleValue(payload.get("score"));
            citations.add(new QaDtos.Citation(
                    citations.size() + 1,
                    chunkId,
                    docId,
                    document.kbId(),
                    document.name(),
                    seq,
                    score,
                    content.substring(0, Math.min(content.length(), 1000))));
        }
        return citations;
    }

    public static String key(long docId, int seq) {
        return docId + ":" + seq;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer intValue(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double doubleValue(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
