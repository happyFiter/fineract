/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.account.jobs.executestandinginstructions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.exception.AbstractPlatformServiceUnavailableException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.jobs.exception.JobExecutionException;
import org.apache.fineract.portfolio.account.data.AccountTransferDTO;
import org.apache.fineract.portfolio.account.data.StandingInstructionData;
import org.apache.fineract.portfolio.account.data.StandingInstructionDuesData;
import org.apache.fineract.portfolio.account.domain.AccountTransferRecurrenceType;
import org.apache.fineract.portfolio.account.domain.StandingInstructionStatus;
import org.apache.fineract.portfolio.account.domain.StandingInstructionType;
import org.apache.fineract.portfolio.account.service.AccountTransfersWritePlatformService;
import org.apache.fineract.portfolio.account.service.StandingInstructionReadPlatformService;
import org.apache.fineract.portfolio.common.domain.PeriodFrequencyType;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.DefaultScheduledDateGenerator;
import org.apache.fineract.portfolio.loanaccount.loanschedule.domain.ScheduledDateGenerator;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.apache.fineract.portfolio.savings.exception.InsufficientAccountBalanceException;
import org.apache.fineract.infrastructure.core.config.FineractProperties;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
@RequiredArgsConstructor
public class CustomExecuteStandingInstructionsTasklet implements Tasklet {

    private final StandingInstructionReadPlatformService standingInstructionReadPlatformService;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseSpecificSQLGenerator sqlGenerator;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;
    private final SavingsAccountAssembler savingsAccountAssembler;
    private final FineractProperties fineractProperties;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        Collection<StandingInstructionData> instructionData = standingInstructionReadPlatformService
                .retrieveAll(StandingInstructionStatus.ACTIVE.getValue());
        List<Throwable> errors = new ArrayList<>();
        for (StandingInstructionData data : instructionData) {
            boolean isDueForTransfer = false;
            AccountTransferRecurrenceType recurrenceType = data.getRecurrenceType();
            StandingInstructionType instructionType = data.getInstructionType();
            LocalDate transactionDate = DateUtils.getBusinessLocalDate();
            if (recurrenceType.isPeriodicRecurrence()) {
                final ScheduledDateGenerator scheduledDateGenerator = new DefaultScheduledDateGenerator();
                PeriodFrequencyType frequencyType = data.getRecurrenceFrequency();
                LocalDate startDate = data.getValidFrom();
                if (frequencyType.isMonthly()) {
                    startDate = startDate.withDayOfMonth(data.getRecurrenceOnDay());
                    if (DateUtils.isBefore(startDate, data.getValidFrom())) {
                        startDate = startDate.plusMonths(1);
                    }
                } else if (frequencyType.isYearly()) {
                    startDate = startDate.withDayOfMonth(data.getRecurrenceOnDay()).withMonth(data.getRecurrenceOnMonth());
                    if (DateUtils.isBefore(startDate, data.getValidFrom())) {
                        startDate = startDate.plusYears(1);
                    }
                }
                isDueForTransfer = scheduledDateGenerator.isDateFallsInSchedule(frequencyType, data.getRecurrenceInterval(), startDate,
                        transactionDate);

            }
            BigDecimal transactionAmount = data.getAmount();
            if (data.getToAccountType().isLoanAccount()
                    && (recurrenceType.isDuesRecurrence() || (isDueForTransfer && instructionType.isDuesAmoutTransfer()))) {
                StandingInstructionDuesData standingInstructionDuesData = standingInstructionReadPlatformService
                        .retriveLoanDuesData(data.getToAccount().getId());
                if (data.getInstructionType().isDuesAmoutTransfer()) {
                    transactionAmount = standingInstructionDuesData.totalDueAmount();
                }
                if (recurrenceType.isDuesRecurrence()) {
                    isDueForTransfer = isDueForTransfer(standingInstructionDuesData);
                }
            }

            if (isDueForTransfer && transactionAmount != null && transactionAmount.compareTo(BigDecimal.ZERO) > 0) {
                final SavingsAccount fromSavingsAccount = null;
                final boolean isRegularTransaction = true;
                final boolean isExceptionForBalanceCheck = false;
                AccountTransferDTO accountTransferDTO = new AccountTransferDTO(transactionDate, transactionAmount,
                        data.getFromAccountType(), data.getToAccountType(), data.getFromAccount().getId(), data.getToAccount().getId(),
                        data.getName() + " Standing instruction trasfer ", null, null, null, null, data.toTransferType(), null, null,
                        data.getTransferType().getValue(), null, null, ExternalId.empty(), null, null, fromSavingsAccount,
                        isRegularTransaction, isExceptionForBalanceCheck);
                final boolean transferCompleted = transferAmountWithPartialPaymentSupport(errors, accountTransferDTO, data.getId());

                if (transferCompleted) {
                    final String updateQuery = "UPDATE m_account_transfer_standing_instructions SET last_run_date = ? where id = ?";
                    jdbcTemplate.update(updateQuery, transactionDate, data.getId());
                }

            }
        }
        if (!errors.isEmpty()) {
            throw new JobExecutionException(errors);
        }
        return RepeatStatus.FINISHED;
    }

    public BigDecimal getAvailableBalance(Long savingsAccountId) {
        SavingsAccount account = savingsAccountAssembler.assembleFrom(savingsAccountId, false);
        return account.getWithdrawableBalance();
    }

    private boolean transferAmountWithPartialPaymentSupport(final List<Throwable> errors, final AccountTransferDTO accountTransferDTO,
            final Long instructionId) {
        BigDecimal availableBalance = getAvailableBalance(accountTransferDTO.getFromAccountId());
        BigDecimal residual = BigDecimal.ZERO;
        if (fineractProperties.getStandingInstruction() != null
                && fineractProperties.getStandingInstruction().getResidualBalanceThreshold() != null) {
            residual = fineractProperties.getStandingInstruction().getResidualBalanceThreshold();
        }
        BigDecimal transferable = availableBalance.subtract(residual);
        if (transferable.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal amount = accountTransferDTO.getTransactionAmount();
        if (amount.compareTo(transferable) > 0) {
            amount = transferable;
        }

        AccountTransferDTO updatedDTO = new AccountTransferDTO(accountTransferDTO.getTransactionDate(), amount,
                accountTransferDTO.getFromAccountType(), accountTransferDTO.getToAccountType(),
                accountTransferDTO.getFromAccountId(), accountTransferDTO.getToAccountId(),
                accountTransferDTO.getDescription(), accountTransferDTO.getLocale(), accountTransferDTO.getFmt(),
                accountTransferDTO.getPaymentDetail(), accountTransferDTO.getFromTransferType(),
                accountTransferDTO.getToTransferType(), accountTransferDTO.getChargeId(),
                accountTransferDTO.getLoanInstallmentNumber(), accountTransferDTO.getTransferType(),
                accountTransferDTO.getAccountTransferDetails(), accountTransferDTO.getNoteText(),
                accountTransferDTO.getTxnExternalId(), accountTransferDTO.getLoan(),
                accountTransferDTO.getToSavingsAccount(), accountTransferDTO.getFromSavingsAccount(),
                accountTransferDTO.isRegularTransaction(), accountTransferDTO.isExceptionForBalanceCheck());

        boolean transferCompleted = true;
        StringBuilder errorLog = new StringBuilder();
        StringBuilder updateQuery = new StringBuilder(
                "INSERT INTO m_account_transfer_standing_instructions_history (standing_instruction_id, " + sqlGenerator.escape("status")
                        + ", amount,execution_time, error_log) VALUES (");
        try {
            accountTransfersWritePlatformService.transferFunds(updatedDTO);
        } catch (final PlatformApiDataValidationException e) {
            errors.add(new Exception("Validation exception while transfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Validation exception while trasfering funds ").append(e.getDefaultUserMessage());
        } catch (final InsufficientAccountBalanceException e) {
            errors.add(new Exception("InsufficientAccountBalance Exception while trasfering funds for standing Instruction id"
                    + instructionId + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("InsufficientAccountBalance Exception ");
        } catch (final AbstractPlatformServiceUnavailableException e) {
            errors.add(new Exception("Platform exception while trasfering funds for standing Instruction id" + instructionId + " from "
                    + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Platform exception while trasfering funds ").append(e.getDefaultUserMessage());
        } catch (Exception e) {
            errors.add(new Exception("Unhandled System Exception while trasfering funds for standing Instruction id" + instructionId
                    + " from " + accountTransferDTO.getFromAccountId() + " to " + accountTransferDTO.getToAccountId(), e));
            errorLog.append("Exception while trasfering funds ").append(e.getMessage());

        }
        updateQuery.append(instructionId).append(",");
        if (errorLog.length() > 0) {
            transferCompleted = false;
            updateQuery.append("'failed'").append(",");
        } else {
            updateQuery.append("'success'").append(",");
        }
        updateQuery.append(updatedDTO.getTransactionAmount().doubleValue());
        updateQuery.append(", now(),");
        updateQuery.append("'").append(errorLog).append("')");
        jdbcTemplate.update(updateQuery.toString());
        return transferCompleted;
    }

    public boolean isDueForTransfer(StandingInstructionDuesData standingInstructionDuesData) {
        return standingInstructionDuesData.dueDate() != null
                && !standingInstructionDuesData.dueDate().isAfter(LocalDate.now(DateUtils.getDateTimeZoneOfTenant()));
    }
}
