package org.apache.fineract.portfolio.account.jobs.executestandinginstructions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.apache.fineract.portfolio.account.PortfolioAccountType;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

public class CustomExecuteStandingInstructionsTaskletTest {

    @Test
    public void testGetAvailableBalanceUsesWithdrawableBalance() {
        SavingsAccountAssembler assembler = Mockito.mock(SavingsAccountAssembler.class);
        SavingsAccount account = Mockito.mock(SavingsAccount.class);
        when(account.getWithdrawableBalance()).thenReturn(BigDecimal.TEN);
        when(assembler.assembleFrom(1L, false)).thenReturn(account);

        FineractProperties props = new FineractProperties();
        CustomExecuteStandingInstructionsTasklet tasklet = new CustomExecuteStandingInstructionsTasklet(null, null, null,
                null, assembler, props);

        BigDecimal result = tasklet.getAvailableBalance(1L);
        assertThat(result).isEqualTo(BigDecimal.TEN);
    }

    @Test
    public void testTransferAmountWithResidualThreshold() {
        SavingsAccountAssembler assembler = Mockito.mock(SavingsAccountAssembler.class);
        SavingsAccount account = Mockito.mock(SavingsAccount.class);
        when(account.getWithdrawableBalance()).thenReturn(BigDecimal.valueOf(50));
        when(assembler.assembleFrom(1L, false)).thenReturn(account);

        AccountTransfersWritePlatformService transferService = Mockito.mock(AccountTransfersWritePlatformService.class);
        doNothing().when(transferService).transferFunds(any(AccountTransferDTO.class));

        FineractProperties props = new FineractProperties();
        FineractProperties.FineractStandingInstructionProperties sip = new FineractProperties.FineractStandingInstructionProperties();
        sip.setResidualBalanceThreshold(BigDecimal.TEN);
        props.setStandingInstruction(sip);

        CustomExecuteStandingInstructionsTasklet tasklet = new CustomExecuteStandingInstructionsTasklet(null, mock(JdbcTemplate.class),
                null, transferService, assembler, props);

        AccountTransferDTO dto = new AccountTransferDTO(LocalDate.now(), BigDecimal.valueOf(45), PortfolioAccountType.SAVINGS,
                PortfolioAccountType.SAVINGS, 1L, 2L, "", null, null, null, null, null, null, null, null, ExternalId.empty(), null,
                null, null, true, false);

        List<Throwable> errors = new ArrayList<>();
        boolean result = tasklet.transferAmountWithPartialPaymentSupport(errors, dto, 1L);
        assertThat(result).isTrue();
        Mockito.verify(transferService).transferFunds(Mockito.argThat(t -> t.getTransactionAmount().equals(BigDecimal.valueOf(40))));
    }
}
