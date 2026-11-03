package com.knowbase.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.knowbase.doc.DocumentRepository;
import org.junit.jupiter.api.Test;

class QaCitationResolverTest {

    @Test
    void acceptsOnlyChunksReturnedByThisRetrievalAndPresentInDatabase() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(10)).thenReturn(Optional.of(document(10, 3)));
        when(documents.findChunkId(10, 2)).thenReturn(501L);
        QaCitationResolver resolver = new QaCitationResolver(documents);

        List<QaDtos.Citation> citations = resolver.resolve(
                List.of(
                        citation(10, 3, 2, "合法引用"),
                        citation(10, 999, 5, "不存在"),
                        citation(12, 3, 0, "跨库")),
                Set.of(3L),
                Set.of("10:2", "10:999"));

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).chunkId()).isEqualTo(501);
        assertThat(citations.get(0).rank()).isEqualTo(1);
        assertThat(citations.get(0).quote()).isEqualTo("合法引用");
    }

    @Test
    void rejectsDocumentWhoseDatabaseKbDoesNotMatchPayload() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(10)).thenReturn(Optional.of(document(10, 9)));
        QaCitationResolver resolver = new QaCitationResolver(documents);

        List<QaDtos.Citation> citations = resolver.resolve(
                List.of(citation(10, 3, 0, "伪造 kb_id")),
                Set.of(3L),
                Set.of("10:0"));

        assertThat(citations).isEmpty();
    }

    private static Map<String, Object> citation(
            long docId, long kbId, int seq, String quote) {
        return Map.of(
                "doc_id", docId,
                "kb_id", kbId,
                "seq", seq,
                "score", 0.8,
                "quote", quote);
    }

    private static DocumentRepository.DocumentRow document(long id, long kbId) {
        return new DocumentRepository.DocumentRow(
                id, kbId, "制度.txt", "txt", 100, "hash", "2026/10/a.txt",
                "ACTIVE", 3, null, null, "2026-10-31", "2026-10-31");
    }
}
