package io.emonlens.server.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.emonlens.common.EmonLensException;

class SafeRegexTest {

    @Test
    void containsSemanticsPreserved() {
        Pattern p = SafeRegex.compile("ECHO");
        assertThat(SafeRegex.matches(p, "ECHO:hello world")).isTrue();
        assertThat(SafeRegex.matches(p, "nothing here")).isFalse();
        assertThat(SafeRegex.matches(p, null)).isFalse();
    }

    @Test
    void rejectsOverlongPattern() {
        assertThatThrownBy(() -> SafeRegex.compile("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1)))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void rejectsInvalidPattern() {
        assertThatThrownBy(() -> SafeRegex.compile("([unclosed"))
                .isInstanceOf(EmonLensException.class)
                .hasMessageContaining("invalid pattern");
    }

    @Test
    void catastrophicBacktrackingTimesOutAsNoMatch() {
        // (a+)+$ 在长串上指数回溯；1s 预算必须兜住（等价语义下匹配不上→false）
        Pattern evil = SafeRegex.compile("(a+)+$");
        String payload = "a".repeat(600) + "X";
        long t0 = System.currentTimeMillis();
        boolean matched = SafeRegex.matches(evil, payload);
        long cost = System.currentTimeMillis() - t0;
        assertThat(matched).isFalse();
        assertThat(cost).as("must not hang the event thread").isLessThan(5_000);
    }
}
