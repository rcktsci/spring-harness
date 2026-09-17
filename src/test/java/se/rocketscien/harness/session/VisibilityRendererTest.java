package se.rocketscien.harness.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityRendererTest {

    @Test
    void singleCompactHidesExactlyCoveredRange() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(5, List.of(new VisibilityRenderer.Cover(1, 3)))),
                6
        );

        assertThat(visible).containsExactly(new VisibilityRenderer.Cover(4, 6));
    }

    @Test
    void rangeBoundsAreInclusive() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(6, List.of(new VisibilityRenderer.Cover(2, 4)))),
                6
        );

        assertThat(visible).containsExactly(
                new VisibilityRenderer.Cover(1, 1),
                new VisibilityRenderer.Cover(5, 6)
        );
    }

    @Test
    void laterCompactHidesEarlierCompact() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(
                        new VisibilityRenderer.IndexedCovers(3, List.of(new VisibilityRenderer.Cover(1, 2))),
                        new VisibilityRenderer.IndexedCovers(6, List.of(new VisibilityRenderer.Cover(1, 5)))
                ),
                7
        );

        assertThat(visible).containsExactly(new VisibilityRenderer.Cover(6, 7));
    }

    @Test
    void compactDoesNotHideItselfEvenIfCovered() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(5, List.of(new VisibilityRenderer.Cover(1, 5)))),
                5
        );

        assertThat(visible).containsExactly(new VisibilityRenderer.Cover(5, 5));
    }

    @Test
    void selfPointMergesWithAdjacentVisibleIntervals() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(3, List.of(new VisibilityRenderer.Cover(1, 3)))),
                5
        );

        assertThat(visible).containsExactly(new VisibilityRenderer.Cover(3, 5));
    }

    @Test
    void disjointCoverRangesAccumulate() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(
                        new VisibilityRenderer.IndexedCovers(4, List.of(new VisibilityRenderer.Cover(1, 2))),
                        new VisibilityRenderer.IndexedCovers(7, List.of(
                                new VisibilityRenderer.Cover(4, 4),
                                new VisibilityRenderer.Cover(6, 6)
                        ))
                ),
                8
        );

        assertThat(visible).containsExactly(
                new VisibilityRenderer.Cover(3, 3),
                new VisibilityRenderer.Cover(5, 5),
                new VisibilityRenderer.Cover(7, 8)
        );
    }

    @Test
    void overlappingCoversMergeIntoSingleHiddenInterval() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(
                        new VisibilityRenderer.IndexedCovers(10, List.of(new VisibilityRenderer.Cover(1, 3))),
                        new VisibilityRenderer.IndexedCovers(20, List.of(new VisibilityRenderer.Cover(2, 8)))
                ),
                10
        );

        assertThat(visible).containsExactly(new VisibilityRenderer.Cover(9, 10));
    }

    @Test
    void compactWithoutCoversHidesNothing() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(3, List.of())),
                5
        );

        assertThat(visible).containsExactly(new VisibilityRenderer.Cover(1, 5));
    }



    @Test
    void wideCoverRangeIsIntervalNotPerSeqMaterialization() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(1_000_001, List.of(new VisibilityRenderer.Cover(1, 1_000_000)))),
                1_000_001
        );

        assertThat(visible).containsExactly(new VisibilityRenderer.Cover(1_000_001, 1_000_001));
    }

    @Test
    void emptyJournalHasNoVisibleIntervals() {
        assertThat(VisibilityRenderer.visibleIntervals(List.of(), 0)).isEmpty();
    }

    @Test
    void selfInsertionAdjacentIntervalsMergedDisjoint() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(3, List.of(new VisibilityRenderer.Cover(3, 3)))),
                5
        );
        assertThat(visible).containsExactly(
                new VisibilityRenderer.Cover(1, 5)
        );
    }

    @Test
    void coversWithLongMaxValueClampedWithoutOverflow() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(new VisibilityRenderer.IndexedCovers(5, List.of(new VisibilityRenderer.Cover(1, Long.MAX_VALUE)))),
                10
        );
        assertThat(visible).containsExactly(
                new VisibilityRenderer.Cover(5, 5)
        );
    }

    @Test
    void coversBeyondJournalClampedNoFailOpen() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(
                        new VisibilityRenderer.IndexedCovers(1, List.of(new VisibilityRenderer.Cover(1, 2))),
                        new VisibilityRenderer.IndexedCovers(5, List.of(new VisibilityRenderer.Cover(100, 200)))
                ),
                10
        );
        assertThat(visible).containsExactly(
                new VisibilityRenderer.Cover(1, 1),
                new VisibilityRenderer.Cover(3, 10)
        );
    }

    @Test
    void mergedIntervalsStrictlyDisjointAndSorted() {
        List<VisibilityRenderer.Cover> visible = VisibilityRenderer.visibleIntervals(
                List.of(
                        new VisibilityRenderer.IndexedCovers(5, List.of(new VisibilityRenderer.Cover(1, 4))),
                        new VisibilityRenderer.IndexedCovers(10, List.of(new VisibilityRenderer.Cover(3, 8)))
                ),
                15
        );
        assertThat(visible).containsExactly(
                new VisibilityRenderer.Cover(9, 15)
        );
    }
}
