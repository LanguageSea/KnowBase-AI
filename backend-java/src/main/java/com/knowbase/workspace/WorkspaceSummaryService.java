package com.knowbase.workspace;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.knowbase.auth.CurrentUser;
import com.knowbase.kb.AuthzService;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceSummaryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private final WorkspaceRepository repository;
    private final AuthzService authzService;

    public WorkspaceSummaryService(WorkspaceRepository repository, AuthzService authzService) {
        this.repository = repository;
        this.authzService = authzService;
    }

    public WorkspaceSummary summary() {
        int userId = CurrentUser.require().id();
        List<Long> readableKbs = authzService.readableKbIds(userId);
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusDays(6).atStartOfDay();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (WorkspaceRepository.DailyQuestionRow row : repository.countQuestionsByDay(userId, since)) {
            counts.put(row.day(), row.count());
        }
        List<DailyQuestion> dailyQuestions = java.util.stream.IntStream.rangeClosed(0, 6)
                .mapToObj(offset -> today.minusDays(6L - offset))
                .map(day -> new DailyQuestion(day.format(DAY), counts.getOrDefault(day.format(DAY), 0L)))
                .toList();
        List<LatestDocument> latestDocuments = repository.latestDocuments(userId, 5).stream()
                .map(row -> new LatestDocument(
                        row.id(), row.kbId(), row.kbName(), row.name(),
                        row.status(), row.createdAt()))
                .toList();
        return new WorkspaceSummary(
                "PERSONAL",
                readableKbs.size(),
                repository.countAccessibleDocuments(userId),
                repository.countConversations(userId),
                repository.countQuestions(userId),
                repository.countProcessingDocuments(userId),
                dailyQuestions,
                latestDocuments);
    }

    public record WorkspaceSummary(String scope, int accessibleKbCount,
            long activeDocumentCount, long conversationCount, long questionCount,
            long processingDocumentCount, List<DailyQuestion> dailyQuestions,
            List<LatestDocument> latestDocuments) {
    }

    public record DailyQuestion(String date, long count) {
    }

    public record LatestDocument(long id, long kbId, String kbName, String name,
            String status, String createdAt) {
    }
}
