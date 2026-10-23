package uk.gegc.quizmaker.features.documentProcess.application;

import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Unit-test callback executor; never evidence of database commit/rollback. */
class ImmediateTransactionTemplate extends TransactionTemplate {
    @Override
    public <T> T execute(TransactionCallback<T> action) {
        return action.doInTransaction(new SimpleTransactionStatus());
    }
}
