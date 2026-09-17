package se.rocketscien.harness.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Рендер видимости (execution-model §5, глоссарий §4): сообщение скрыто ⟺ существует более
 * поздний COMPACT, покрывающий его (включительно по границам); COMPACT-событие само не скрывает
 * себя, но может быть скрыто последующим COMPACT.
 *
 * <p>Работает диапазонами, не материализуя множество покрытых seq: покрытие в миллион событий —
 * один интервал, а не миллион элементов.</p>
 */
public final class VisibilityRenderer {

    private VisibilityRenderer() {
    }

    /**
     * Видимые интервалы seq в пространстве {@code [1..lastSeq]}: комплемент объединения
     * {@code covers} c возвратом точек {@code compactSeq}, скрытых только своим собственным cover.
     */
    public static List<Cover> visibleIntervals(List<IndexedCovers> compacts, long lastSeq) {
        if (lastSeq <= 0) {
            return List.of();
        }
        List<IndexedCovers> clampedCompacts = compacts.stream()
                .map(indexed -> new IndexedCovers(
                        indexed.compactSeq(),
                        indexed.covers().stream()
                                .map(cover -> new Cover(
                                        Math.max(1, cover.fromSeq()),
                                        Math.min(cover.toSeq(), lastSeq)
                                ))
                                .filter(cover -> cover.fromSeq() <= cover.toSeq())
                                .toList()
                ))
                .toList();
        List<Cover> visible = complement(mergedCovers(clampedCompacts), lastSeq);
        for (Long selfPoint : selfHiddenOnlyPoints(clampedCompacts)) {
            if (selfPoint >= 1 && selfPoint <= lastSeq) {
                visible = insertPoint(visible, selfPoint);
            }
        }
        return visible;
    }

    private static List<long[]> mergedCovers(List<IndexedCovers> compacts) {
        List<Cover> covers = compacts.stream()
                .flatMap(indexed -> indexed.covers().stream())
                .sorted(Comparator.comparingLong(Cover::fromSeq))
                .toList();
        List<long[]> merged = new ArrayList<>();
        for (Cover cover : covers) {
            if (!merged.isEmpty() && cover.fromSeq() <= merged.getLast()[1] + 1) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], cover.toSeq());
            } else {
                merged.add(new long[]{cover.fromSeq(), cover.toSeq()});
            }
        }
        return merged;
    }

    private static List<Cover> complement(List<long[]> hidden, long lastSeq) {
        List<Cover> visible = new ArrayList<>();
        long cursor = 1;
        for (long[] interval : hidden) {
            if (interval[0] > cursor) {
                visible.add(new Cover(cursor, Math.min(interval[0] - 1, lastSeq)));
            }
            cursor = Math.max(cursor, interval[1] + 1);
        }
        if (cursor <= lastSeq) {
            visible.add(new Cover(cursor, lastSeq));
        }
        return visible;
    }

    /**
     * Точки compactSeq, попавшие в объединение covers, но накрытые только собственным cover
     * (никакой другой COMPACT их не покрывает) — COMPACT не скрывает сам себя.
     */
    private static Set<Long> selfHiddenOnlyPoints(List<IndexedCovers> compacts) {
        Set<Long> selfPoints = new HashSet<>();
        for (IndexedCovers compact : compacts) {
            selfPoints.add(compact.compactSeq());
        }
        Set<Long> result = new HashSet<>();
        for (Long point : selfPoints) {
            boolean hiddenByOther = compacts.stream()
                    .filter(other -> other.compactSeq() != point)
                    .flatMap(other -> other.covers().stream())
                    .anyMatch(cover -> cover.fromSeq() <= point && point <= cover.toSeq());
            boolean hiddenBySelf = compacts.stream()
                    .filter(self -> self.compactSeq() == point)
                    .flatMap(self -> self.covers().stream())
                    .anyMatch(cover -> cover.fromSeq() <= point && point <= cover.toSeq());
            if (hiddenBySelf && !hiddenByOther) {
                result.add(point);
            }
        }
        return result;
    }

    private static List<Cover> insertPoint(List<Cover> intervals, long point) {
        List<Cover> result = new ArrayList<>(intervals.size() + 1);
        boolean inserted = false;
        for (Cover interval : intervals) {
            if (point >= interval.fromSeq() && point <= interval.toSeq()) {
                result.add(interval);
                inserted = true;
            } else if (point + 1 == interval.fromSeq()) {
                if (!result.isEmpty() && result.getLast().toSeq() + 1 >= interval.fromSeq()) {
                    Cover last = result.removeLast();
                    result.add(new Cover(last.fromSeq(), interval.toSeq()));
                } else {
                    result.add(new Cover(point, interval.toSeq()));
                }
                inserted = true;
            } else if (point - 1 == interval.toSeq()) {
                if (!result.isEmpty() && result.getLast().toSeq() + 1 >= interval.fromSeq()) {
                    Cover last = result.removeLast();
                    result.add(new Cover(last.fromSeq(), point));
                } else {
                    result.add(new Cover(interval.fromSeq(), point));
                }
                inserted = true;
            } else if (point < interval.fromSeq()) {
                result.add(new Cover(point, point));
                result.add(interval);
                inserted = true;
            } else {
                result.add(interval);
            }
        }
        if (!inserted) {
            result.add(new Cover(point, point));
        }
        return result;
    }

    /**
     * Диапазон покрытия (включительно).
     */
    public record Cover(long fromSeq, long toSeq) {
    }

    /**
     * covers одного COMPACT-события вместе с его собственным seq.
     */
    public record IndexedCovers(long compactSeq, List<Cover> covers) {
    }
}
