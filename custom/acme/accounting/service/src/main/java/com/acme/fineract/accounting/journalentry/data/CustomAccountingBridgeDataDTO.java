package com.acme.fineract.accounting.journalentry.data;

import java.math.BigDecimal;

public interface CustomAccountingBridgeDataDTO {
    Long getOfficeId();

    String getCurrencyCode();

    Long getLoanProductId();

    Long getSavingsAccountId();

    BigDecimal getPrincipalPortion();

    BigDecimal getFeesPortion();

    BigDecimal getVatPortion();

    BigDecimal getNetDisbursalAmount();
}
