package com.paygate.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    boolean existsByTransactionRef(String transactionRef);

    List<LedgerEntry> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    @Query("select coalesce(sum(case when e.direction = com.paygate.ledger.LedgerEntry.Direction.CREDIT "
            + "then e.amount else -e.amount end), 0) "
            + "from LedgerEntry e "
            + "where e.merchantId = :merchantId and e.account = :account")
    long sumCreditBalance(@Param("merchantId") UUID merchantId,
                          @Param("account") LedgerEntry.LedgerAccount account);

    @Query("select coalesce(sum(case when e.direction = com.paygate.ledger.LedgerEntry.Direction.DEBIT "
            + "then e.amount else -e.amount end), 0) "
            + "from LedgerEntry e "
            + "where e.merchantId = :merchantId and e.account = :account")
    long sumDebitBalance(@Param("merchantId") UUID merchantId,
                         @Param("account") LedgerEntry.LedgerAccount account);

    @Query("select e.account as account, "
            + "coalesce(sum(case when e.direction = com.paygate.ledger.LedgerEntry.Direction.CREDIT "
            + "then e.amount else -e.amount end), 0) as balance "
            + "from LedgerEntry e "
            + "where e.merchantId = :merchantId "
            + "group by e.account")
    List<AccountBalance> sumBalancesByAccount(@Param("merchantId") UUID merchantId);

    interface AccountBalance {

        LedgerEntry.LedgerAccount getAccount();

        long getBalance();
    }
}
