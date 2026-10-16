package com.hengzhang;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.*;

public final class Domain {
    private Domain() {}
    public enum Source { PLATFORM, CLEARING, SETTLEMENT }
    public enum Kind { TRANSACTION, FUND, BALANCE }
    public enum MatchMode { ONE_TO_ONE, GROUPED }
    public enum BusinessType { PAYMENT, REFUND, FEE, TRANSFER }
    public enum TradeStatus { SUCCESS, PENDING, FAILED }
    public enum ResolutionMethod { MANUAL_CONFIRM, CONFIRM_LOSS, CONFIRM_INCOME, CORRECTED_EXTERNALLY }
    public record ChannelInput(@NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="[A-Z0-9_-]{2,40}") String code,
        @NotBlank @Size(max=80) String merchantNo, @NotNull LocalDate openingDate,
        @NotBlank String openingBalance) {}
    public record ProjectInput(@NotBlank @Size(max=100) String name, @NotNull Kind kind,
        @Positive long channelId, @NotNull Source leftSource, @NotNull Source rightSource,
        @NotNull MatchMode matchMode, @NotBlank String tolerance,
        @Min(0) @Max(30) int settlementDays, @NotNull LocalDate startDate,
        boolean enabled, boolean autoRun, @Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") @NotNull String scheduleTime) {}
    public record RunInput(@Positive long projectId, @NotNull LocalDate businessDate,
        boolean rerun, String leftAdjustment, String rightAdjustment) {}
    public record ResolutionInput(@NotBlank String amount, @NotNull ResolutionMethod method,
        @NotBlank @Size(min=3,max=1000) String note, @NotBlank @Size(max=1000) String evidence) {}
    public record ReviewInput(boolean approve, @NotBlank @Size(max=1000) String note) {}
    public record LoginInput(@NotBlank String username, @NotBlank String password) {}
    public record ImportRow(@NotBlank @Size(max=100) String externalId,
        @NotBlank @Size(max=100) String orderNo, @NotNull BusinessType businessType,
        @NotBlank String amount, @NotNull TradeStatus status, @NotNull LocalDate tradeDate) {}
    public record JsonImport(@Positive long channelId, @NotNull Source source,
        @NotNull LocalDate businessDate, @NotNull @Size(max=10000) java.util.List<@jakarta.validation.Valid ImportRow> rows) {}
    public record SourceRecord(String externalId, String orderNo, BusinessType type,
        long amount, TradeStatus status, LocalDate date) {}
    public record Outcome(String key, String result, long leftAmount, long rightAmount,
        int leftCount, int rightCount, String detail) {}

    public static long cents(String value) {
        if (value == null || !value.matches("-?\\d{1,12}(?:\\.\\d{1,2})?"))
            throw new Problem("金额必须为最多 12 位整数、2 位小数的十进制数字");
        try { return new BigDecimal(value).movePointRight(2).longValueExact(); }
        catch (ArithmeticException e) { throw new Problem("金额超出范围"); }
    }
    public static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
    public static class Problem extends RuntimeException {
        public Problem(String message) { super(message); }
    }
}
