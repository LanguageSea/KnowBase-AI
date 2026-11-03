package com.knowbase.doc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PointIdsTest {

    @Test
    void matchesPythonUuid5PointId() {
        assertThat(PointIds.forChunk(7, 2))
                .isEqualTo("e0133bae-3876-54f3-af36-2305a90ebe24");
    }
}
