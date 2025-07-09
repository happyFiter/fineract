package com.acme.fineract.accounting.journalentry.service;

import com.acme.fineract.accounting.journalentry.data.CustomAccountingBridgeDataDTO;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.apache.fineract.accounting.closure.domain.GLClosureRepository;
import org.apache.fineract.accounting.common.AccountingConstants;
import org.apache.fineract.accounting.financialactivityaccount.domain.FinancialActivityAccountRepositoryWrapper;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.glaccount.domain.GLAccountRepository;
import org.apache.fineract.accounting.glaccount.exception.GLAccountNotFoundException;
import org.apache.fineract.accounting.glaccount.service.GLAccountReadPlatformService;
import org.apache.fineract.accounting.journalentry.command.JournalEntryCommandFromApiJsonDeserializer;
import org.apache.fineract.accounting.journalentry.data.AccountingBridgeDataDTO;
import org.apache.fineract.accounting.journalentry.domain.JournalEntry;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryRepository;
import org.apache.fineract.accounting.journalentry.domain.JournalEntryType;
import org.apache.fineract.accounting.journalentry.service.AccountingProcessorHelper;
import org.apache.fineract.accounting.journalentry.service.JournalEntryWritePlatformServiceJpaRepositoryImpl;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMapping;
import org.apache.fineract.accounting.producttoaccountmapping.domain.ProductToGLAccountMappingRepository;
import org.apache.fineract.accounting.rule.domain.AccountingRuleRepository;
import org.apache.fineract.infrastructure.core.domain.AppUser;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.monetary.domain.OrganisationCurrencyRepositoryWrapper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.organisation.office.domain.OfficeRepositoryWrapper;
import org.apache.fineract.portfolio.PortfolioProductType;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProduct;
import org.apache.fineract.portfolio.loanproduct.domain.LoanProductRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepositoryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomJournalEntryWritePlatformServiceJpaRepositoryImpl extends JournalEntryWritePlatformServiceJpaRepositoryImpl {

    private final OfficeRepositoryWrapper officeRepositoryWrapper;
    private final GLAccountRepository glAccountRepository;
    private final AccountingProcessorHelper helper;
    private final PlatformSecurityContext context;
    private final ProductToGLAccountMappingRepository productToGLAccountMappingRepository;
    private final LoanProductRepository loanProductRepository;
    private final SavingsAccountRepositoryWrapper savingsAccountRepository;

    public CustomJournalEntryWritePlatformServiceJpaRepositoryImpl(GLClosureRepository glClosureRepository,
            GLAccountRepository glAccountRepository, JournalEntryRepository glJournalEntryRepository,
            OfficeRepositoryWrapper officeRepositoryWrapper, AccountingProcessorForLoanFactory accountingProcessorForLoanFactory,
            AccountingProcessorForSavingsFactory accountingProcessorForSavingsFactory,
            AccountingProcessorForSharesFactory accountingProcessorForSharesFactory, AccountingProcessorHelper helper,
            JournalEntryCommandFromApiJsonDeserializer fromApiJsonDeserializer, AccountingRuleRepository accountingRuleRepository,
            GLAccountReadPlatformService glAccountReadPlatformService, OrganisationCurrencyRepositoryWrapper organisationCurrencyRepository,
            PlatformSecurityContext context, PaymentDetailWritePlatformService paymentDetailWritePlatformService,
            FinancialActivityAccountRepositoryWrapper financialActivityAccountRepositoryWrapper,
            CashBasedAccountingProcessorForClientTransactions accountingProcessorForClientTransactions,
            ProductToGLAccountMappingRepository productToGLAccountMappingRepository,
            LoanProductRepository loanProductRepository, SavingsAccountRepositoryWrapper savingsAccountRepository) {
        super(glClosureRepository, glAccountRepository, glJournalEntryRepository, officeRepositoryWrapper,
                accountingProcessorForLoanFactory, accountingProcessorForSavingsFactory, accountingProcessorForSharesFactory, helper,
                fromApiJsonDeserializer, accountingRuleRepository, glAccountReadPlatformService, organisationCurrencyRepository, context,
                paymentDetailWritePlatformService, financialActivityAccountRepositoryWrapper, accountingProcessorForClientTransactions);
        this.officeRepositoryWrapper = officeRepositoryWrapper;
        this.glAccountRepository = glAccountRepository;
        this.helper = helper;
        this.context = context;
        this.productToGLAccountMappingRepository = productToGLAccountMappingRepository;
        this.loanProductRepository = loanProductRepository;
        this.savingsAccountRepository = savingsAccountRepository;
    }

    @Override
    @Transactional
    public void createJournalEntriesForLoan(final AccountingBridgeDataDTO accountingBridgeData) {
        if (accountingBridgeData instanceof CustomAccountingBridgeDataDTO customDTO) {
            BigDecimal principal = Optional.ofNullable(customDTO.getPrincipalPortion()).orElse(BigDecimal.ZERO);
            BigDecimal fees = Optional.ofNullable(customDTO.getFeesPortion()).orElse(BigDecimal.ZERO);
            BigDecimal vat = Optional.ofNullable(customDTO.getVatPortion()).orElse(BigDecimal.ZERO);
            BigDecimal netDisbursal = Optional.ofNullable(customDTO.getNetDisbursalAmount()).orElse(BigDecimal.ZERO);

            Long loanProductId = customDTO.getLoanProductId();

            GLAccount loanPortfolioGL = fetchGLAccountForLoanProduct(loanProductId,
                    AccountingConstants.CashAccountsForLoan.LOAN_PORTFOLIO.getValue());
            GLAccount feesGL = fetchGLAccountForLoanProduct(loanProductId,
                    AccountingConstants.CashAccountsForLoan.INCOME_FROM_FEES.getValue());
            GLAccount liabilityTransferGL = fetchGLAccountForLoanProduct(loanProductId,
                    AccountingConstants.CashAccountsForLoan.FUND_SOURCE.getValue());
            GLAccount vatGL = deriveVatGlAccount(loanProductId);
            GLAccount savingsControlGL = deriveSavingsControlGlAccount(customDTO.getSavingsAccountId());

            Office office = officeRepositoryWrapper.findOneWithNotFoundDetection(customDTO.getOfficeId());
            String currencyCode = customDTO.getCurrencyCode();
            LocalDate txnDate = LocalDate.now();

            persistJournalEntry(office, loanPortfolioGL, currencyCode, txnDate, JournalEntryType.DEBIT, principal,
                    "Loan disbursal principal");

            persistJournalEntry(office, liabilityTransferGL, currencyCode, txnDate, JournalEntryType.CREDIT, netDisbursal,
                    "Net disbursal to client");

            if (fees.compareTo(BigDecimal.ZERO) > 0) {
                persistJournalEntry(office, feesGL, currencyCode, txnDate, JournalEntryType.CREDIT, fees, "Disbursal fees");
            }

            if (vat.compareTo(BigDecimal.ZERO) > 0 && vatGL != null) {
                persistJournalEntry(office, vatGL, currencyCode, txnDate, JournalEntryType.CREDIT, vat, "Disbursal VAT");
            }

            persistJournalEntry(office, liabilityTransferGL, currencyCode, txnDate, JournalEntryType.DEBIT, netDisbursal,
                    "Transfer to savings");

            persistJournalEntry(office, savingsControlGL, currencyCode, txnDate, JournalEntryType.CREDIT, netDisbursal,
                    "Credit to savings account");

        } else {
            super.createJournalEntriesForLoan(accountingBridgeData);
        }
    }

    private GLAccount deriveVatGlAccount(Long loanProductId) {
        return loanProductRepository.findById(loanProductId).map(lp -> {
            for (Charge charge : lp.getCharges()) {
                if (charge.getTaxGroup() != null) {
                    return charge.getTaxGroup().getTaxGroupMappings().stream()
                            .map(m -> m.getTaxComponent().getCreditAcount())
                            .filter(a -> a != null)
                            .findFirst()
                            .orElse(null);
                }
            }
            return null;
        }).orElse(null);
    }

    private GLAccount deriveSavingsControlGlAccount(Long savingsAccountId) {
        if (savingsAccountId == null) {
            return null;
        }
        SavingsAccount savingsAccount = savingsAccountRepository.findOneWithNotFoundDetection(savingsAccountId);
        Long productId = savingsAccount.getSavingsProductId();
        ProductToGLAccountMapping mapping = productToGLAccountMappingRepository.findCoreProductToFinAccountMapping(productId,
                PortfolioProductType.SAVING.getValue(), AccountingConstants.CashAccountsForSavings.SAVINGS_CONTROL.getValue());
        if (mapping == null) {
            throw new GLAccountNotFoundException(
                    "No GL account mapping found for savingsControl and savingsProductId: " + productId);
        }
        return mapping.getGlAccount();
    }

    private GLAccount fetchGLAccountForLoanProduct(Long loanProductId, int financialAccountType) {
        ProductToGLAccountMapping mapping = productToGLAccountMappingRepository.findCoreProductToFinAccountMapping(loanProductId,
                PortfolioProductType.LOAN.getValue(), financialAccountType);
        if (mapping == null) {
            throw new GLAccountNotFoundException(
                    "No GL account mapping found for type: " + financialAccountType + " and loanProductId: " + loanProductId);
        }
        return mapping.getGlAccount();
    }

    private GLAccount fetchGLAccount(String glCode) {
        return glAccountRepository.findOneByGlCode(glCode).orElseThrow(() -> new GLAccountNotFoundException(glCode));
    }

    private void persistJournalEntry(Office office, GLAccount glAccount, String currencyCode, LocalDate txnDate, JournalEntryType type,
            BigDecimal amount, String description) {
        JournalEntry entry = JournalEntry.createNew(office, null, glAccount, currencyCode, generateTransactionId(office.getId()), true,
                txnDate, type, amount, description, null, null, null, null, null, null, null);
        helper.persistJournalEntry(entry);
    }

    private String generateTransactionId(final Long officeId) {
        final AppUser user = this.context.authenticatedUser();
        final Long time = System.currentTimeMillis();
        final String uniqueVal = String.valueOf(time) + user.getId() + officeId;
        return Long.toHexString(Long.parseLong(uniqueVal));
    }
}
