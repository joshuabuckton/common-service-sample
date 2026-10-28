package preferences.service.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import preferences.service.domain.CustomerPreferencesEvent;
import preferences.service.domain.CustomerPreferencesEvent.PreferencesUpdated;

/**
 * The audit history. One row per customer holds every change, oldest first, and the set of callers
 * that made them. The row of a customer grows with every write.
 */
@Component(id = "preference-history")
public class PreferenceHistoryView extends View {

  public record Change(
    boolean email,
    boolean sms,
    boolean phone,
    String changedBy,
    String reason,
    Instant changedAt
  ) {}

  public record HistoryEntry(String customerId, Set<String> changers, List<Change> changes) {
    static HistoryEntry empty(String customerId) {
      return new HistoryEntry(customerId, Set.of(), List.of());
    }

    HistoryEntry with(Change change) {
      var newChangers = new LinkedHashSet<>(changers);
      newChangers.add(change.changedBy());
      var newChanges = new ArrayList<>(changes);
      newChanges.add(change);
      return new HistoryEntry(customerId, newChangers, newChanges);
    }
  }

  public record HistoryEntries(List<HistoryEntry> histories) {}

  @Consume.FromEventSourcedEntity(CustomerPreferencesEntity.class)
  public static class History extends TableUpdater<HistoryEntry> {

    public Effect<HistoryEntry> onEvent(CustomerPreferencesEvent event) {
      return switch (event) {
        case PreferencesUpdated updated -> {
          var current = rowState() == null ? HistoryEntry.empty(updated.customerId()) : rowState();
          var change = new Change(
            updated.email(),
            updated.sms(),
            updated.phone(),
            updated.changedBy(),
            updated.reason(),
            updated.changedAt()
          );
          yield effects().updateRow(current.with(change));
        }
      };
    }
  }

  @Query("SELECT * AS histories FROM preference_history WHERE customerId = :customerId")
  public QueryEffect<HistoryEntries> forCustomer(String customerId) {
    return queryResult();
  }

  /** The customers that a caller changed, with their whole history. */
  @Query("SELECT * AS histories FROM preference_history WHERE :changedBy = ANY(changers)")
  public QueryEffect<HistoryEntries> touchedBy(String changedBy) {
    return queryResult();
  }
}
