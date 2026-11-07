package uk.gegc.quizmaker.features.result.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import static uk.gegc.quizmaker.features.result.domain.model.AutomaticResultException.require;
import static uk.gegc.quizmaker.features.result.domain.model.AutomaticResultException.Reason.*;

/** Immutable binary-result/v1 fact. Contains no responses, answer keys or release authority. */
public record AutomaticResult(UUID id, int version, UUID submissionId, UUID attemptId, UUID formId,
        UUID policySnapshotId, UUID quizRevisionId, String policyHash, Score score, Instant createdAt, List<Item> items) {
    public static final String CONTRACT="binary-result/v1";
    public AutomaticResult {
        require(version==1,UNSUPPORTED);
        require(id!=null && submissionId!=null && attemptId!=null && formId!=null && policySnapshotId!=null
                && quizRevisionId!=null && policyHash!=null && policyHash.matches("[0-9a-f]{64}")
                && score!=null && createdAt!=null && items!=null && !items.isEmpty(),CORRUPT);
        createdAt=createdAt.truncatedTo(ChronoUnit.MICROS);
        items=List.copyOf(items);
        var ids=new HashSet<UUID>(); var responses=new HashSet<UUID>();
        long earned=0;
        for(int i=0;i<items.size();i++) {
            var item=items.get(i);
            require(item.position()==i && ids.add(item.formItemId()) && (item.responseId()==null || responses.add(item.responseId())),CORRUPT);
            earned=Math.addExact(earned,item.earned());
        }
        require(score.earned()==earned && score.possible()==items.size(),CORRUPT);
    }
    public record Item(UUID formItemId, UUID questionRevisionId, UUID responseId, int position,
                       ContractVersion responseContract, ContractVersion scoringContract, int earned) {
        public Item {
            require(formItemId!=null && questionRevisionId!=null && position>=0 && responseContract!=null
                    && scoringContract!=null && earned>=0 && earned<=1 && (responseId!=null || earned==0),CORRUPT);
        }
    }
    public record Score(long earned,long possible,int basisPoints,int thresholdBasisPoints,boolean passed) {
        public Score {
            require(possible>0 && earned>=0 && earned<=possible && thresholdBasisPoints>=0 && thresholdBasisPoints<=10000, CORRUPT);
            var numerator=BigDecimal.valueOf(earned).multiply(BigDecimal.valueOf(10000));
            require(basisPoints==numerator.divide(BigDecimal.valueOf(possible),0,RoundingMode.HALF_UP).intValueExact()
                    && passed==(numerator.compareTo(BigDecimal.valueOf(possible).multiply(BigDecimal.valueOf(thresholdBasisPoints)))>=0),CORRUPT);
        }
        public static Score calculate(long earned,long possible,int threshold) {
            require(possible>0 && earned>=0 && earned<=possible && threshold>=0 && threshold<=10000,INVALID_INPUT);
            var numerator=BigDecimal.valueOf(earned).multiply(BigDecimal.valueOf(10000));
            return new Score(earned,possible,numerator.divide(BigDecimal.valueOf(possible),0,RoundingMode.HALF_UP).intValueExact(),
                    threshold,numerator.compareTo(BigDecimal.valueOf(possible).multiply(BigDecimal.valueOf(threshold)))>=0);
        }
    }
    @Override public String toString() { return "AutomaticResult[version="+version+"]"; }
}
