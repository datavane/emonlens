package io.emonlens.server.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ClaimAggregatorTest {

    @Test
    void allPass() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(
                claims("PASS", "PASS", "PASS", "PASS", "PASS"), 0.8, "BLOCK");
        assertThat(result.getLabel()).isEqualTo("PASS");
        assertThat(result.getReasonCode()).isEqualTo("CLAIM_PASS");
        assertThat(result.getPassRatio()).isEqualTo(1.0);
    }

    @Test
    void anyFailVetoes() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(
                claims("PASS", "PASS", "FAIL", "PASS"), 0.8, "BLOCK");
        assertThat(result.getLabel()).isEqualTo("FAIL");
        assertThat(result.getReasonCode()).isEqualTo("CLAIM_FAIL");
    }

    @Test
    void unknownBlocksByDefault() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(
                claims("PASS", "PASS", "UNKNOWN"), 0.8, "BLOCK");
        assertThat(result.getLabel()).isEqualTo("UNKNOWN");
        assertThat(result.getReasonCode()).isEqualTo("CLAIM_UNKNOWN");
        assertThat(result.getUnknownCount()).isEqualTo(1);
    }

    @Test
    void unknownCanPassWhenPolicyAllows() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(
                claims("PASS", "PASS", "UNKNOWN"), 0.8, "PASS");
        assertThat(result.getLabel()).isEqualTo("PASS");
        assertThat(result.getUnknownCount()).isEqualTo(1);
    }

    @Test
    void unknownCanFail() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(
                claims("PASS", "PASS", "UNKNOWN"), 0.8, "FAIL");
        assertThat(result.getLabel()).isEqualTo("FAIL");
    }

    @Test
    void allUnknownIsUnknown() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(
                claims("UNKNOWN", "UNKNOWN", "UNKNOWN"), 0.8, "PASS");
        assertThat(result.getLabel()).isEqualTo("UNKNOWN");
        assertThat(result.getReasonCode()).isEqualTo("ALL_CLAIMS_UNKNOWN");
        assertThat(result.getPassRatio()).isZero();
    }

    @Test
    void ratioBelowThresholdFails() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(
                claims("PASS", "PASS", "FAIL", "FAIL", "FAIL"), 0.8, "BLOCK");
        assertThat(result.getLabel()).isEqualTo("FAIL");
    }

    @Test
    void emptyClaimsIsUnknown() {
        ClaimAggregator.Result result = ClaimAggregator.aggregate(List.of(), 0.8, "BLOCK");
        assertThat(result.getLabel()).isEqualTo("UNKNOWN");
        assertThat(result.getReasonCode()).isEqualTo("INVALID_JUDGE_OUTPUT");
    }

    @Test
    void nullVerdictCountsAsUnknown() {
        Claim claim = claim(null);
        ClaimAggregator.Result result = ClaimAggregator.aggregate(List.of(claim), 0.8, "BLOCK");
        assertThat(result.getLabel()).isEqualTo("UNKNOWN");
    }

    private List<Claim> claims(String... verdicts) {
        List<Claim> claims = new ArrayList<>();
        int index = 1;
        for (String verdict : verdicts) {
            Claim claim = claim(verdict);
            claim.setId("c" + index++);
            claims.add(claim);
        }
        return claims;
    }

    private Claim claim(String verdict) {
        Claim claim = new Claim();
        claim.setId("c");
        claim.setText("claim");
        claim.setVerdict(verdict);
        return claim;
    }
}
