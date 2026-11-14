package io.github.signalbot.domain.model;

import java.math.BigDecimal;

/**
 * Free cash in one currency.
 *
 * @param currency                currency of this balance
 * @param balance                 total balance, native currency
 * @param balanceKzt              balance converted to KZT
 * @param reservedByOpenBuyOrders amount already committed to open BUY orders
 *                                (native currency); spendable = balance - reserved
 */
public record CashBalance(
        Ccy currency,
        BigDecimal balance,
        BigDecimal balanceKzt,
        BigDecimal reservedByOpenBuyOrders
) {
}
