package io.github.signalbot.persistence;

import io.github.signalbot.application.review.RMultiple;
import io.github.signalbot.config.FxConverter;
import io.github.signalbot.config.PaperWalletProperties;
import io.github.signalbot.domain.model.Action;
import io.github.signalbot.domain.model.Ccy;
import io.github.signalbot.domain.model.FeeBreakdown;
import io.github.signalbot.domain.model.PaperFill;
import io.github.signalbot.domain.model.PriceSnapshot;
import io.github.signalbot.domain.model.Venue;
import io.github.signalbot.domain.model.Verdict;
import io.github.signalbot.domain.port.MarketDataFeed;
import io.github.signalbot.domain.port.PaperWallet;
import io.github.signalbot.persistence.LedgerStore.TradeRecord;
import io.github.signalbot.persistence.PositionStore.PaperPositionRow;
import io.github.signalbot.persistence.TradeQueryStore.OpeningBuy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Simulated execution. The ONLY execution path in the service: no real order is
 * ever placed. Cash, fees and tax settle in KZT via the configured FX rate;
 * instrument prices stay in the instrument currency. Fees come from {@link FeeModel}.
 */
@Service
public class PaperWalletImpl implements PaperWallet {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    private final ObjectProvider<MarketDataFeed> feedProvider;
    private final PositionStore positions;
    private final CashStore cash;
    private final LedgerStore ledger;
    private final FxConverter fx;
    private final FeeModel feeModel;
    private final PaperWalletProperties props;
    private final TradeQueryStore trades;

    public PaperWalletImpl(ObjectProvider<MarketDataFeed> feedProvider, PositionStore positions, CashStore cash,
                           LedgerStore ledger, FxConverter fx, FeeModel feeModel, PaperWalletProperties props,
                           TradeQueryStore trades) {
        this.feedProvider = feedProvider;
        this.positions = positions;
        this.cash = cash;
        this.ledger = ledger;
        this.fx = fx;
        this.feeModel = feeModel;
        this.props = props;
        this.trades = trades;
    }

    @Override
    @Transactional
    public PaperFill apply(Verdict verdict) {
        MarketDataFeed feed = feedProvider.getIfAvailable();
        if (feed == null) {
            throw new IllegalStateException("No market data feed configured; cannot price a fill");
        }
        PriceSnapshot quote = feed.snapshot(verdict.symbol());
        return applyAt(verdict, quote.last());
    }

    @Override
    @Transactional
    public PaperFill applyAt(Verdict verdict, BigDecimal fillPrice, Long executionId) {
        if (!verdict.isTrade()) {
            throw new IllegalArgumentException("HOLD verdicts are not executable");
        }
        Optional<PaperPositionRow> existing = positions.find(verdict.symbol());
        Ccy ccy = existing.map(PaperPositionRow::priceCurrency).orElse(Ccy.USD);
        Venue venue = existing.map(PaperPositionRow::venue).orElse(Venue.US);
        return verdict.action() == Action.BUY
                ? buy(verdict, fillPrice, ccy, venue, existing, executionId)
                : sell(verdict, fillPrice, ccy, venue, existing, executionId);
    }

    private PaperFill buy(Verdict v, BigDecimal fillPrice, Ccy ccy, Venue venue, Optional<PaperPositionRow> existing,
                          Long executionId) {
        BigDecimal qty = v.quantity();
        BigDecimal notionalKzt = kzt(fx.toKzt(fillPrice.multiply(qty), ccy));
        FeeBreakdown fee = feeModel.compute(venue, qty, notionalKzt);
        cash.adjust(Ccy.KZT, notionalKzt.add(fee.totalKzt()).negate());

        Instant now = Instant.now();
        PaperPositionRow updated = existing
                .map(p -> new PaperPositionRow(p.secCode(), p.isin(), p.venue(),
                        p.quantity().add(qty), weightedAvg(p, qty, fillPrice), p.priceCurrency(), p.openedAt()))
                .orElseGet(() -> new PaperPositionRow(v.symbol(), null, venue, qty, fillPrice, ccy, now));
        positions.upsert(updated);

        ledger.insertTrade(new TradeRecord(v.symbol(), Action.BUY, qty, fillPrice,
                fee.totalKzt(), notionalKzt, BigDecimal.ZERO, BigDecimal.ZERO, ccy, v.symbol(), now, executionId));
        return new PaperFill(v.symbol(), Action.BUY, qty, fillPrice, fee, BigDecimal.ZERO, BigDecimal.ZERO, now);
    }

    private PaperFill sell(Verdict v, BigDecimal fillPrice, Ccy ccy, Venue venue, Optional<PaperPositionRow> existing,
                           Long executionId) {
        PaperPositionRow pos = existing.orElseThrow(
                () -> new IllegalStateException("No position to sell for " + v.symbol()));
        BigDecimal qty = v.quantity().min(pos.quantity());
        BigDecimal notionalKzt = kzt(fx.toKzt(fillPrice.multiply(qty), ccy));
        FeeBreakdown fee = feeModel.compute(venue, qty, notionalKzt);
        BigDecimal realizedKzt = kzt(fx.toKzt(fillPrice.subtract(pos.avgEntryPrice()).multiply(qty), ccy));
        BigDecimal taxKzt = realizedKzt.signum() > 0 ? kzt(realizedKzt.multiply(props.taxRate())) : BigDecimal.ZERO;

        cash.adjust(Ccy.KZT, notionalKzt.subtract(fee.totalKzt()));
        Optional<BigDecimal> resultR = executionId == null
                ? Optional.empty()
                : resultR(pos, qty, realizedKzt, fee.totalKzt());
        BigDecimal remaining = pos.quantity().subtract(qty);
        if (remaining.signum() <= 0) {
            positions.delete(pos.secCode());
        } else {
            positions.upsert(new PaperPositionRow(pos.secCode(), pos.isin(), pos.venue(),
                    remaining, pos.avgEntryPrice(), pos.priceCurrency(), pos.openedAt()));
        }

        Instant now = Instant.now();
        long id = ledger.insertTrade(new TradeRecord(v.symbol(), Action.SELL, qty, fillPrice,
                fee.totalKzt(), notionalKzt, realizedKzt, taxKzt, ccy, v.symbol(), now, executionId));
        resultR.ifPresent(r -> trades.setResultR(executionId, r));
        if (taxKzt.signum() > 0) {
            ledger.accrueTax(id, realizedKzt, taxKzt);
        }
        ledger.addRealizedPnl(LocalDate.ofInstant(now, ALMATY), realizedKzt);
        return new PaperFill(v.symbol(), Action.SELL, qty, fillPrice, fee, realizedKzt, taxKzt, now);
    }

    /**
     * The SELL's result in R ({@link RMultiple}): 1R is the sum of {@code executions.risk_kzt} of the BUYs that
     * opened the position (not {@code position_guards}: the guard is deleted when an exit fires and overwritten by
     * a second BUY). Empty when any of those BUYs has no execution, no 1R or no recorded fee — the trade is then
     * left out of R statistics rather than given a partial 1R.
     */
    private Optional<BigDecimal> resultR(PaperPositionRow pos, BigDecimal soldQty, BigDecimal realizedKzt,
                                         BigDecimal sellFeeKzt) {
        List<OpeningBuy> buys = trades.openingBuys(pos.secCode(), pos.openedAt());
        if (buys.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal riskKzt = BigDecimal.ZERO;
        BigDecimal buyFeesKzt = BigDecimal.ZERO;
        BigDecimal boughtQty = BigDecimal.ZERO;
        for (OpeningBuy buy : buys) {
            if (buy.riskKzt() == null || buy.feeTotalKzt() == null) {
                return Optional.empty();
            }
            riskKzt = riskKzt.add(buy.riskKzt());
            buyFeesKzt = buyFeesKzt.add(buy.feeTotalKzt());
            boughtQty = boughtQty.add(buy.quantity());
        }
        if (boughtQty.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal net = RMultiple.netResultKzt(realizedKzt, sellFeeKzt, buyFeesKzt, soldQty, boughtQty);
        return RMultiple.of(net, riskKzt);
    }

    private static BigDecimal weightedAvg(PaperPositionRow p, BigDecimal addQty, BigDecimal addPrice) {
        BigDecimal totalCost = p.quantity().multiply(p.avgEntryPrice()).add(addQty.multiply(addPrice));
        return totalCost.divide(p.quantity().add(addQty), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal kzt(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.HALF_UP);
    }
}
