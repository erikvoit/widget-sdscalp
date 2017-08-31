import com.optionscity.freeway.api.*;
import com.optionscity.freeway.api.messages.MarketBidAskMessage;
import com.optionscity.freeway.api.messages.OrderMessage;
import com.optionscity.freeway.api.messages.TradeMessage;
import common.sdscalp.BollingerBandSignal;
import common.sdscalp.TrackedOrder;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.optionscity.freeway.api.helpers.Pricing.findClosestPriceDown;
import static com.optionscity.freeway.api.helpers.Pricing.findClosestPriceUp;

/**
 * Created by evoit on 8/29/2017.
 */
public class SirTradesALot extends AbstractJob {

    // Job defined Class variables
    private int baseTradeSize;
    private double minBandwidth;
    private String jobId;
    private String instrumentId;
    private double stopDistance;
    /**
     * 0=Minimal, 1=Basic, 2=Full, 3=Debug
     */
    private int verbosity = 0;

    // Order Tracking
    private Map<Long, TrackedOrder> orderMap;
    private Map<Long, List<TradeMessage>> _tradeMessagesByParentOrderId;

    // Local position tracking variables
    private int netPosition = 0;
    private int tradeCount = 0;
    private double pnl = 0;
    private long currentStop; // orderId of the currently active stop order

    // Control variables
    private boolean bandsInitialized = false;
    private double pivotPrice = 0;
    private double trailingTarget = 0;
    private boolean sellStopPending = false;
    private boolean buyStopPending = false;

    // Time series variables
    private double fastLowerBand;
    private double fastLowerBandHk;
    private double fastLowerBand1k;
    private double fastLowerBand3k;
    private double fastMiddleBand;
    private double fastUpperBand;
    private double fastUpperBandHk;
    private double fastUpperBand1k;
    private double fastUpperBand3k;
    private double bandWidth;

    @Override
    public void install(IJobSetup setup)
    {
        setup.addVariable("verbosity", "log detail level: 0=Minimal, 1=Basic, 2=Full, 3=Debug", "int", "2");
        setup.addVariable("Instrument", "Instrument to process live tick data for", "instrumentId", "ES-20170915-F");
        setup.addVariable("Base Trade Size", "Default trade size for this job", "int", "1");
        setup.addVariable("Min Bandwidth Threshold", "Minimum bandwidth to switch from profit target to trailing stop", "double", "2.5");
        setup.addVariable("Trail Distance", "Distance to trail a stop behind the pivot", "double", ".5");
    }
    @Override
    public void begin(IContainer container)
    {
        super.begin(container);
        initialize();
    }
    /**
     * Subscribe to event listeners
     * assign class variables
     */
    private void initialize()
    {
        jobId = container.getMyJobId();
        container.subscribeToSignals();
        container.subscribeToTradeMessages();
        instrumentId=container.getVariable("Instrument");
        baseTradeSize=getIntVar("Base Trade Size");
        minBandwidth=getDoubleVar("Min Bandwidth Threshold");
        stopDistance=getDoubleVar("Trail Distance");
        verbosity=getIntVar("verbosity");
        container.filterMarketMessages(instrumentId);
        this.orderMap = new HashMap<Long, TrackedOrder>(100);
        this._tradeMessagesByParentOrderId = new HashMap<Long, List<TradeMessage>>(200);
    }
    public void onTimer()
    {
        if (tradeCount == 0){
            initialPosition();
        }
    }
    public void onTrade(TradeMessage m)
    {
       /*
       if (jobId.equals(m.jobId)) {

            if (m.side == Order.Side.SELL) {
                this.netPosition-=m.quantity;
                this.tradeCount+=m.quantity;
                log("onTrade: Order "+m.orderQuoteId+" Sold "+m.quantity+ " at "+m.price);
            }
            else {
                this.netPosition+=m.quantity;
                this.tradeCount+=m.quantity;
                log("onTrade: Order "+m.orderQuoteId+" bought "+m.quantity+ " at "+m.price);
            }
            log("onTrade: The net position is now "+this.netPosition);
            log("onTrade: The job has traded "+this.tradeCount+" times.");
        }
       */
    }
    public void onOrder(OrderMessage m)
    {
        try
        {
            if (m == null)
                return;

            long orderId = m.orderId;
            Order.Status status = m.status;

            // TrackedOrder.state: 0=Undefined, 1=PendingBooked, 2=PendingCancel, 3=Active, 4=Filled, 5=Cancelled)
            if (status == Order.Status.BOOKED)
            {
                // mark order state as Active
                TrackedOrder to = this.orderMap.get(orderId);
                if (to != null && (to.state == 1 || to.state == 0))
                    to.state = 3;
            }
            else if (status == Order.Status.CANCELLED || status == Order.Status.EXPIRED ||
                    status == Order.Status.PULLED || status == Order.Status.REJECTED)
            {
                TrackedOrder to = this.orderMap.get(orderId);
                if (to != null)
                {
                    int priorState = to.state;
                    int priorSignedQty = to.signedQty;
                    int qty = Math.abs(to.signedQty);
                    // reset signedQty on TrackedOrder
                    to.signedQty = 0;
                    to.state = 5;

                    // NOTE: we don't remove cxled orders that were partially filled from the map because they
                    // may have an auto hedge response still pending
                    //if (Math.abs(to.signedOriginalQty) == qty)
                    //this.orderMap.remove(orderId);

                    if (to.instId != null)
                    {
                        if (priorState != 2)
                        {
                            // this shouldn't happen other than manual user interference or some other rare cases
                            if (this.verbosity > 0)
                                log("warning: unexpected order status (" + status.toString() + ") received for order (" + orderId + "); assuming user manual cancellation");

                            if (orderId == currentStop)
                            { //if user cancels the current stop/target reset the trading logic
                                this.currentStop = 0;
                            }
                            to = null;
                        }
                    }
                }
                //TODO work on this stop pending logic...
                if (orderId == currentStop){
                    if (buyStopPending)
                    {
                        buyStopPending=false;
                    } else if (sellStopPending)
                    {
                        sellStopPending=false;
                    }
                    log("onOrder: Stop Order "+currentStop+" successfully canceled.");
                    this.currentStop = 0;
                }
            }
            else if (status == Order.Status.FILLED || status == Order.Status.PARTIAL)
            {
                TrackedOrder to = this.orderMap.get(orderId);
                if (to != null)
                {
                    Order ord = null;
                    // NOTE: if prior state = 2 (PendingCancel), then we got filled while attempting to cancel
                    int priorState = to.state;

                    // NOTE: 'qty' will contain the abs(incrementally filled qty), and 'signedQty' will
                    // contain the signed incrementally filled qty
                    int qty = 0, signedQty = 0;
                    if (status == Order.Status.FILLED)
                    {
                        // fully filled
                        signedQty = to.signedQty;
                        qty = Math.abs(signedQty);
                        // reset signedQty on TrackedOrder
                        to.signedQty = 0;
                        to.state = 4;
                        // NOTE: leave in order map because auto hedge response may be pending
                        if (to.signedOriginalQty < 0)
                        {
                            this.netPosition+=to.signedOriginalQty;
                            this.tradeCount+=Math.abs(to.signedOriginalQty);
                            log("onOrder: Order "+to.orderId +" Sold "+to.signedOriginalQty + " at "+to.price);
                        }
                        else
                        {
                            this.netPosition+=to.signedOriginalQty;
                            this.tradeCount+=to.signedOriginalQty;
                            log("onOrder: Order "+to.orderId +" bought "+to.signedOriginalQty + " at "+to.price);
                        }
                        log("onOrder: The net position is now "+this.netPosition);
                        log("onOrder: The job has traded "+this.tradeCount+" times.");
                        this.currentStop = 0;

                    }
                    else
                    {
                        // partial fill
                        ord = orders().getOrder(orderId);
                        if (ord != null)
                        {
                            int trackedRemainingQty = Math.abs(to.signedQty);
                            int actualRemainingQty = Math.max(ord.orderQuantity - ord.filledQuantity, 0);
                            qty = trackedRemainingQty - actualRemainingQty;
                            signedQty = qty * ord.side.multiplier();
                            // resync signedQty to correct value
                            to.signedQty = ord.side.multiplier() * Math.abs(actualRemainingQty);
                        }
                        else
                        {
                            // probably erroneous situation--safest behavior is to default to full fill
                            signedQty = to.signedQty;
                            qty = Math.abs(signedQty);
                            // reset signedQty on TrackedOrder
                            to.signedQty = 0;
                        }
                    }
                }
            }
            else if (status == Order.Status.NEW)
            {
                // this status is not important
            }
            else
            {
                if (status == null)
                    log("warning: unhandled OrderMessage received with null status");
                else
                    log("warning: unhandled OrderMessage received with status '" + status.name() + "'");
            }

            if (status.isComplete())
            {
                // we'll never hear about this orderId again, so remove it
                this.orderMap.remove(orderId);
            }
        }
        catch (Exception e)
        {
            log("error: an exception occurred in onOrder():");
            this.logException(e);
        }
    }
    public void onMarketBidAsk(MarketBidAskMessage m)
    {
        Prices prices = instruments().getMarketPrices(m.instrumentId);
        if (this.tradeCount >= this.baseTradeSize)
        { // Note: If this is not true we haven't taken an initial position so don't do anything
            if (this.currentStop == 0) // there is no stop order placed
            {
                if (this.netPosition > 0)
                { // we are long
                    if (prices.bid > this.pivotPrice)
                    {
                        this.pivotPrice = prices.bid;
                        this.trailingTarget = this.pivotPrice - this.stopDistance;
                        log ("onMarketBidAsk: New pivot price is "+pivotPrice+ " the trailing target is "+trailingTarget);
                    } else if (prices.bid <= trailingTarget)
                    {
                        this.currentStop = submitOrder(OrderRequest.stopMarketOrder(this.instrumentId, Order.Side.SELL, this.baseTradeSize * 2, this.trailingTarget), this.trailingTarget);
                        log("onMarketBidAsk: Bid is below the trailing target price. Net position is "+this.netPosition+" placing "+this.baseTradeSize*2+" lot stop order "+this.currentStop+" at price "+this.trailingTarget+".");

                    }
                }else if (this.netPosition < 0)
                { // Note: Else net position is less than 1
                    if (prices.ask < pivotPrice)
                    {
                        this.pivotPrice = prices.ask;
                        this.trailingTarget = this.pivotPrice + this.stopDistance;
                        log ("onMarketBidAsk: New pivot price is "+pivotPrice+ " the trailing target is "+trailingTarget);
                    } else if (prices.ask >= trailingTarget)
                    {
                        log("onMarketBidAsk: Ask is above the trailing target price. Net position is "+this.netPosition+" placing "+this.baseTradeSize*2+" lot stop order "+this.currentStop+" at price "+this.trailingTarget+".");
                        this.currentStop = submitOrder(OrderRequest.stopMarketOrder(instrumentId, Order.Side.BUY, this.baseTradeSize * 2, this.trailingTarget), this.trailingTarget);
                    }
                }
            } else
            { // trail the stop
                if (this.netPosition > 0)
                { // we are long
                    if (prices.bid > this.pivotPrice)
                    { // trailing stop logic
                        cancelOrder(currentStop);
                        this.sellStopPending = true;
                        this.pivotPrice = prices.bid;
                        this.trailingTarget = this.pivotPrice - this.stopDistance;
                        log ("onMarketBidAsk: New pivot price is "+pivotPrice+ " the trailing target is "+trailingTarget);
                    }
                } else if (this.netPosition < 0)
                { //we are short
                    if (prices.ask < this.pivotPrice)
                    { // trailing stop logic
                        cancelOrder(this.currentStop);
                        this.buyStopPending = true;
                        this.pivotPrice = prices.ask;
                        this.trailingTarget = this.pivotPrice + this.stopDistance;
                        log ("onMarketBidAsk: New pivot price is "+pivotPrice+ " the trailing target is "+trailingTarget);
                    }
                }
            }
        }
    }
    /**
     * Take an initial position
     */
    private void initialPosition()
    {
        if (bandsInitialized)
        {
            double midMarket = getCleanMidMarketPrice(instrumentId);
            long initialPosition = 0;
            if (midMarket >= fastMiddleBand)
            {
                initialPosition = submitOrder(OrderRequest.buy(instrumentId, baseTradeSize), Double.NaN);
                pivotPrice = findClosestPriceDown(instrumentId, midMarket);
                trailingTarget = pivotPrice - stopDistance;
                log("Initial position opened. Pivot is "+this.pivotPrice+" the trailing target is now "+trailingTarget);
            }else
            {
                initialPosition =submitOrder(OrderRequest.sell(instrumentId, baseTradeSize), Double.NaN);
                pivotPrice = findClosestPriceUp(instrumentId, midMarket);
                trailingTarget = pivotPrice + stopDistance;
                log("Initial position opened. Pivot is "+this.pivotPrice+" the trailing target is now "+trailingTarget);
            }
        }
    }
    // helper methods
    public void onSignal(BollingerBandSignal bbSignal)
    {
        //log("Received an update to the Bollinger bands. The new low band is "+bbSignal.lowerBand + " the new middle band is "+bbSignal.middleBand +" The upper band is "+bbSignal.upperBand);
        this.fastLowerBand=bbSignal.lowerBand;
        this.fastMiddleBand=bbSignal.middleBand;
        this.fastUpperBand=bbSignal.upperBand;
        this.fastLowerBand1k=bbSignal.lowerBand1k;
        this.fastUpperBand1k=bbSignal.upperBand1k;
        this.fastLowerBandHk=bbSignal.lowerBandHk;
        this.fastUpperBandHk=bbSignal.upperBandHk;
        this.fastLowerBand3k=bbSignal.lowerBand3k;
        this.fastUpperBand3k=bbSignal.upperBand3k;
        this.bandWidth=bbSignal.bandWidth;
        if (!bandsInitialized){
            bandsInitialized = true;
        }
    }
    private long submitOrder(OrderRequest order, double initialPrice)
    {
        long orderId = -1;
        int signedQty = order.side.multiplier() * order.quantity;
        // submit new order to exchange
        orderId = orders().submit(order);
        TrackedOrder to = new TrackedOrder(orderId, order.instrumentId, order.until, signedQty, initialPrice);
        this.orderMap.put(orderId, to);
        return (orderId);
    }
    /**
     *
     * @param orderId
     * @param changeOrderQty Must be True for 'newSignedQty' parameter to be relevant.
     * @param newSignedQty The newly desired signed remaining qty. Sign must stay consistent with the original side. Not relevant if 'changeOrderQty' is False.
     * @param price Pass 0 to leave price unmodified.
     * @return Returns True if order modify succeeded; otherwise False.
     */
    private boolean modifyOrder(long orderId, boolean changeOrderQty, int newSignedQty, double price)
    {
        if (changeOrderQty && newSignedQty == 0)
        {
            // really a cancel
            this.cancelOrder(orderId);
            return true;
        }

        TrackedOrder to = this.orderMap.get(orderId);
        if (to != null && to.IsActive())
        {
            if (to.signedQty == 0)
            {
                // order has already been fully filled
                return false;
            }
            // check that side has stayed consistent
            if (changeOrderQty && Math.signum(to.signedOriginalQty) != Math.signum(newSignedQty))
            {
                log("error: order (" + orderId + ") cannot be modified to a different side");
                return false;
            }

            if (price == 0)
                price = to.price;
            int newOrigSignedOrderQty = 0;
            if (!changeOrderQty)
                newOrigSignedOrderQty = to.signedOriginalQty;
            else
            {
                int qtyDelta = newSignedQty - to.signedQty;
                newOrigSignedOrderQty = to.signedOriginalQty + qtyDelta;
            }

            // NOTE: the 2nd 'qty' param here refers to the order's 'original' qty (and *NOT* the
            // remaining qty)
            if (orders().modify(orderId, Math.abs(newOrigSignedOrderQty), price))
            {
                to.price = price;
                if (changeOrderQty)
                {
                    // NOTE: must modify the original qty, as well, otherwise we might infer phantom fills
                    to.signedOriginalQty = newOrigSignedOrderQty;
                    to.signedQty = newSignedQty;
                }
                return true;
            }
            else
            {
                // order modify failed
                Order ordSync = null;
                boolean orderIsNoLongerActive = false;
                try
                {
                    ordSync = orders().getOrder(orderId);
                    if (ordSync.status.isComplete())
                        orderIsNoLongerActive = true;
                    else
                    {
                        // see if only a price re-sync is necessary
                        int actualRemainingQty = Math.max(ordSync.orderQuantity - ordSync.filledQuantity, 0);
                        if (actualRemainingQty == Math.abs(to.signedQty) && !equalsWithinTolerance(to.price, ordSync.orderPrice, 0.000001) && ordSync.orderPrice > 0)
                            to.price = ordSync.orderPrice;
                        else if (this.verbosity > 2)
                        {
                            if (changeOrderQty)
                                log("warning: order (" + orderId + ") failed to be modified to new 'remaining' qty " + newSignedQty + " @ " + formatVariableFine(price));
                            else
                                log("warning: order (" + orderId + ") failed to be modified to new price " + formatVariableFine(price));
                        }
                    }
                }
                catch (Exception e)
                {
                    orderIsNoLongerActive = true;
                }

                if (orderIsNoLongerActive)
                {
                    // in almost all cases, this scenario is due to a working order that has just been
                    // filled (but prior to us receiving the onOrder() message for it)
                    if (this.verbosity > 2)
                    {
                        if (changeOrderQty)
                            log("warning: order (" + orderId + ") failed to be modified to new 'remaining' qty " + newSignedQty + " @ " + formatVariableFine(price));
                        else
                            log("warning: order (" + orderId + ") failed to be modified to new price " + formatVariableFine(price));
                        log("warning: order (" + orderId + ") is no longer listed as active; order has likely just been filled");
                    }
                }
                return false;
            }
        }
        else
        {
            log("error: order (" + orderId + ") cannot be modified because it is no longer active");
            return false;
        }
    }
    private void cancelOrder(long orderId)
    {
        // NOTE: does this depend on order already being in "Active" state?
        orders().cancel(orderId);
        // mark order state as Pending Cancel
        if (this.orderMap.containsKey(orderId))
            this.orderMap.get(orderId).state = 2;
    }
    public static String formatVariableFine(double dbl)
    {
        DecimalFormat dF = new DecimalFormat("0.0####");
        return (dF.format(dbl));
    }
    private static boolean equalsWithinTolerance(double a, double b, double tolerance)
    {
        return (Math.abs(a - b) < tolerance);
    }
    private void logException(Exception e)
    {
        if (e.getMessage() != null)
            log(e.getMessage());
        for (StackTraceElement ste : e.getStackTrace())
        {
            log(ste.toString());
        }
    }
    private double getCleanMidMarketPrice(String instrumentId)
    {
        Prices topOfBook = instruments().getTopOfBook(instrumentId);
        if (topOfBook.ask_size == 0 && topOfBook.bid_size == 0)
        {
            return Double.NaN;
        } else if (topOfBook.ask_size == 0) {
            return topOfBook.bid;
        } else if (topOfBook.bid_size == 0) {
            return topOfBook.ask;
        } else {
            return 0.5*(topOfBook.ask + topOfBook.bid);
        }
    }
    /**
     * @param orderId returns true if this order Id is in the map
     */
    private boolean isLive(long orderId)
    {
        if (orderMap.containsKey(orderId)){
            return orderMap.get(orderId).isLive();
        } else {
            return false;
        }
    }
}
