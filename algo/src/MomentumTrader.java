import com.optionscity.freeway.api.*;
import com.optionscity.freeway.api.messages.MarketBidAskMessage;
import com.optionscity.freeway.api.messages.OrderMessage;
import com.optionscity.freeway.api.messages.TradeMessage;
import com.optionscity.freeway.api.helpers.Pricing;
import common.sdscalp.BollingerBandSignal;
import common.sdscalp.TrackedOrder;

import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.optionscity.freeway.api.helpers.Pricing.findClosestPriceDown;
import static com.optionscity.freeway.api.helpers.Pricing.findClosestPriceUp;

/**
 * Created by evoit on 8/9/2017.
 *
 */
public class MomentumTrader extends AbstractJob {
    String jobId;
    String instrumentId;

    // Local position tracking variables
    int netPosition = 0;
    int tradeCount = 0;
    double pnl = 0;

    // Time series variables
    private double fastLowerBand;
    private double fastLowerBand1k;
    private double fastMiddleBand;
    private double fastUpperBand;
    private double fastUpperBand1k;
    private double bandWidth;

    // Control variables
    private boolean bandsInitialized = false;
    boolean clearToTrade; // Don't enter new orders if certain market conditions perhaps bid ask spread or missing side?
    long currentTarget = 0; //The orderId of the current profit target
    /**
     * 0=Undefined, 1=Above EMA, 2=Above 1K, 3=Above 2K, -1 Below EMA, -2=Below 1K, -3 Below 2K
     */
    int lastZone = 0;
    int thisZone = 0;

    // Job defined Class variables
    int baseTradeSize;
    double minBandwidth;
    /**
     * 0=Minimal, 1=Basic, 2=Full, 3=Debug
     */
    private int verbosity = 0;

    // Order Tracking
    private Map<Long, TrackedOrder> orderMap;
    private Map<Long, List<TradeMessage>> _tradeMessagesByParentOrderId;

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("verbosity", "log detail level: 0=Minimal, 1=Basic, 2=Full, 3=Debug", "int", "2");
        setup.addVariable("Instrument", "Instrument to process live tick data for", "instrumentId", "ES-20170915-F");
        setup.addVariable("Base Trade Size", "Default trade size for this job", "int", "1");
        setup.addVariable("Min Bandwidth Threshold", "Minimum bandwidth to switch from profit target to trailing stop", "double", "2.5");
    }
    @Override
    public void begin(IContainer container) {
        super.begin(container);
        initialize();
        //initialPosition();
    }
    public void onTimer(){
        if (tradeCount == 0){
            initialPosition();
        }
    }
    /**
     * Subscribe to event listeners
     * assign class variables
     */
    private void initialize(){
        jobId = container.getMyJobId();
        container.subscribeToSignals();
        container.subscribeToTradeMessages();
        instrumentId=container.getVariable("Instrument");
        baseTradeSize=getIntVar("Base Trade Size");
        minBandwidth=getDoubleVar("Min Bandwidth Threshold");
        verbosity=getIntVar("verbosity");
        container.filterMarketMessages(instrumentId);
        this.orderMap = new HashMap<Long, TrackedOrder>(100);
        this._tradeMessagesByParentOrderId = new HashMap<Long, List<TradeMessage>>(200);
    }
    /**
     * Process onTrade events
     */
    public void onTrade(TradeMessage m){
        if (jobId.equals(m.jobId)) {
            if (m.side == Order.Side.SELL) {
                netPosition-=m.quantity;
                tradeCount+=m.quantity;
            }
            else {
                netPosition+=m.quantity;
                tradeCount+=m.quantity;
            }
        }
    }
    public void onOrder(OrderMessage m) {
        try
        {
            if (m == null)
                return;

            long orderId = m.orderId;
            Order.Status status = m.status;

            // TrackedOrder.State: 0=Undefined, 1=PendingBooked, 2=PendingCancel, 3=Active, 4=Filled, 5=Cancelled)
            if (status == Order.Status.BOOKED)
            {
                // mark order state as Active
                TrackedOrder to = this.orderMap.get(orderId);
                if (to != null && (to.State == 1 || to.State == 0))
                    to.State = 3;
            }
            else if (status == Order.Status.CANCELLED || status == Order.Status.EXPIRED ||
                    status == Order.Status.PULLED || status == Order.Status.REJECTED)
            {
                TrackedOrder to = this.orderMap.get(orderId);
                if (to != null)
                {
                    int priorState = to.State;
                    int priorSignedQty = to.SignedQty;
                    int qty = Math.abs(to.SignedQty);
                    // reset SignedQty on TrackedOrder
                    to.SignedQty = 0;
                    to.State = 5;

                    // NOTE: we don't remove cxled orders that were partially filled from the map because they
                    // may have an auto hedge response still pending
                    //if (Math.abs(to.SignedOriginalQty) == qty)
                        //this.orderMap.remove(orderId);

                    if (to.InstId != null)
                    {
                        if (priorState != 2 && (priorState != 4 || priorState != 5)) {
                            // this shouldn't happen other than manual user interference or some other rare cases
                            if (this.verbosity > 0)
                                log("warning: unexpected order status (" + status.toString() + ") received for order (" + orderId + "); assuming user manual cancellation");

                            to = null;
                        }
                    }
                }
            }
            else if (status == Order.Status.FILLED || status == Order.Status.PARTIAL)
            {
                TrackedOrder to = this.orderMap.get(orderId);
                if (to != null)
                {
                    Order ord = null;
                    // NOTE: if prior state = 2 (PendingCancel), then we got filled while attempting to cancel
                    int priorState = to.State;
                    // NOTE: 'qty' will contain the abs(incrementally filled qty), and 'signedQty' will
                    // contain the signed incrementally filled qty
                    int qty = 0, signedQty = 0;
                    if (status == Order.Status.FILLED)
                    {
                        // fully filled
                        signedQty = to.SignedQty;
                        qty = Math.abs(signedQty);
                        // reset SignedQty on TrackedOrder
                        to.SignedQty = 0;
                        to.State = 4;
                        // NOTE: leave in order map because auto hedge response may be pending
                    }
                    else
                    {
                        // partial fill
                        ord = orders().getOrder(orderId);
                        if (ord != null)
                        {
                            int trackedRemainingQty = Math.abs(to.SignedQty);
                            int actualRemainingQty = Math.max(ord.orderQuantity - ord.filledQuantity, 0);
                            qty = trackedRemainingQty - actualRemainingQty;
                            signedQty = qty * ord.side.multiplier();
                            // resync SignedQty to correct value
                            to.SignedQty = ord.side.multiplier() * Math.abs(actualRemainingQty);
                        }
                        else
                        {
                            // probably erroneous situation--safest behavior is to default to full fill
                            signedQty = to.SignedQty;
                            qty = Math.abs(signedQty);
                            // reset SignedQty on TrackedOrder
                            to.SignedQty = 0;
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
            this.LogException(e);
        }
    }
    public void onMarketBidAsk(MarketBidAskMessage m){
        // Note: If this is not true we haven't taken an initial position so don't do anything
        if (tradeCount >= baseTradeSize){
            Prices prices = instruments().getMarketPrices(m.instrumentId);
            getZone(prices);
            // TODO build logic for trading narrow bandwidth first we will just take a position and reverse on crossing back over the upper band etc...
            // after this is working we will define a separate strategy for when the bands are wider.  This will use a trailing stop to catch the bigger swing moves.
            //if (bandWidth < minBandwidth) {
                if (netPosition > 0){ // we are long so looking for the upper band...
                    if (prices.bid > fastMiddleBand && prices.bid < fastUpperBand1k){ // Market price is above the EMA
                        // Note: 0=Undefined, 1=Above EMA, 2=Above 1K, 3=Above 2K, -1 Below EMA, -2=Below 1K, -3 Below 2K
                    } else if (prices.bid > fastUpperBand1k && prices.bid < fastUpperBand) {
                    } else if (prices.bid > fastUpperBand) { // market is in profit taking or trailing stop territory!
                        if (!isLive(currentTarget)){
                            double initialTargetPrice = findClosestPriceDown(instrumentId, fastUpperBand);
                            currentTarget = submitOrder(OrderRequest.stopMarketOrder(instrumentId, Order.Side.SELL, baseTradeSize * 2, initialTargetPrice), initialTargetPrice);
                        }
                    } else if (prices.ask < fastMiddleBand) { // Market is below the EMA and we are long
                        // we either just took a profit or are stopping a loss
                        if (lastZone >= 0) {
                            if (!isLive(currentTarget)){
                                double initialTargetPrice = findClosestPriceDown(instrumentId, fastLowerBand1k);
                                currentTarget = submitOrder(OrderRequest.stopMarketOrder(instrumentId, Order.Side.SELL, baseTradeSize * 2, initialTargetPrice), initialTargetPrice);
                            }
                        }
                    } else {
                        //We are straddling the average price so we really don't have to do anything here other than maybe make sure we don't whipsaw ourselves to death!
                    }
                }else { // Note: Else net position is less than 1
                    // we a short so targeting the lower band
                    if (prices.ask < fastMiddleBand && prices.ask > fastLowerBand1k){ // Market price is below the EMA
                    } else if (prices.ask < fastLowerBand1k && prices.ask > fastLowerBand) {
                    } else if (prices.ask < fastLowerBand){
                        if (!isLive(currentTarget)){
                            double initialTargetPrice = findClosestPriceUp(instrumentId, fastLowerBand);
                            currentTarget = submitOrder(OrderRequest.stopMarketOrder(instrumentId, Order.Side.BUY, baseTradeSize * 2, initialTargetPrice), initialTargetPrice);
                        }
                    } else if (prices.bid > fastMiddleBand){ // Market is above fast EMA and we are short
                        if (lastZone <=0){
                            if (!isLive(currentTarget)){
                                double initialTargetPrice = findClosestPriceUp(instrumentId, fastUpperBand1k);
                                currentTarget = submitOrder(OrderRequest.stopMarketOrder(instrumentId, Order.Side.BUY, baseTradeSize * 2, initialTargetPrice), initialTargetPrice);
                            }
                        }
                    }
                }
            //}
        }
    }
    public void onSignal(BollingerBandSignal bbSignal){
        log("Received an update to the Bollinger bands. The new low band is "+bbSignal.lowerBand + " the new middle band is "+bbSignal.middleBand +" The upper band is "+bbSignal.upperBand);
        fastLowerBand=bbSignal.lowerBand;
        fastMiddleBand=bbSignal.middleBand;
        fastUpperBand=bbSignal.upperBand;
        fastLowerBand1k=bbSignal.lowerBand1k;
        fastUpperBand1k=bbSignal.upperBand1k;
        bandWidth=bbSignal.bandWidth;
        if (!bandsInitialized){
            bandsInitialized = true;
        }
    }
    /**
     * Take an initial position
     */
    public void initialPosition(){
        if (bandsInitialized){
            double midMarket = getCleanMidMarketPrice(instrumentId);
            if (midMarket >= fastMiddleBand) {
                submitOrder(OrderRequest.buy(instrumentId, baseTradeSize), Double.NaN);
                netPosition+=baseTradeSize;
            }else {
                submitOrder(OrderRequest.sell(instrumentId, baseTradeSize), Double.NaN);
                netPosition-=baseTradeSize;
            }
            tradeCount+=baseTradeSize;
        }
    }
    private long submitOrder(OrderRequest order, double initialPrice) {
        log("Net position is "+netPosition+" current zone is "+thisZone+" last zone is "+lastZone+" placing new order");
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
            this.CancelOrder(orderId);
            return true;
        }

        TrackedOrder to = this.orderMap.get(orderId);
        if (to != null && to.IsActive())
        {
            if (to.SignedQty == 0)
            {
                // order has already been fully filled
                return false;
            }
            // check that side has stayed consistent
            if (changeOrderQty && Math.signum(to.SignedOriginalQty) != Math.signum(newSignedQty))
            {
                log("error: order (" + orderId + ") cannot be modified to a different side");
                return false;
            }

            if (price == 0)
                price = to.Price;
            int newOrigSignedOrderQty = 0;
            if (!changeOrderQty)
                newOrigSignedOrderQty = to.SignedOriginalQty;
            else
            {
                int qtyDelta = newSignedQty - to.SignedQty;
                newOrigSignedOrderQty = to.SignedOriginalQty + qtyDelta;
            }

            // NOTE: the 2nd 'qty' param here refers to the order's 'original' qty (and *NOT* the
            // remaining qty)
            if (orders().modify(orderId, Math.abs(newOrigSignedOrderQty), price))
            {
                to.Price = price;
                if (changeOrderQty)
                {
                    // NOTE: must modify the original qty, as well, otherwise we might infer phantom fills
                    to.SignedOriginalQty = newOrigSignedOrderQty;
                    to.SignedQty = newSignedQty;
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
                        if (actualRemainingQty == Math.abs(to.SignedQty) && !EqualsWithinTolerance(to.Price, ordSync.orderPrice, 0.000001) && ordSync.orderPrice > 0)
                            to.Price = ordSync.orderPrice;
                        else if (this.verbosity > 2)
                        {
                            if (changeOrderQty)
                                log("warning: order (" + orderId + ") failed to be modified to new 'remaining' qty " + newSignedQty + " @ " + FormatVariableFine(price));
                            else
                                log("warning: order (" + orderId + ") failed to be modified to new price " + FormatVariableFine(price));
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
                            log("warning: order (" + orderId + ") failed to be modified to new 'remaining' qty " + newSignedQty + " @ " + FormatVariableFine(price));
                        else
                            log("warning: order (" + orderId + ") failed to be modified to new price " + FormatVariableFine(price));
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
    private void CancelOrder(long orderId)
    {
        // NOTE: does this depend on order already being in "Active" state?
        orders().cancel(orderId);
        // mark order state as Pending Cancel
        if (this.orderMap.containsKey(orderId))
            this.orderMap.get(orderId).State = 2;
    }
    // helper methods
    public static String FormatVariableFine(double dbl)
    {
        DecimalFormat dF = new DecimalFormat("0.0####");
        return (dF.format(dbl));
    }
    private static boolean EqualsWithinTolerance(double a, double b, double tolerance)
    {
        return (Math.abs(a - b) < tolerance);
    }
    private void LogException(Exception e)
    {
        if (e.getMessage() != null)
            log(e.getMessage());
        for (StackTraceElement ste : e.getStackTrace())
        {
            log(ste.toString());
        }
    }
    private double getCleanMidMarketPrice(String instrumentId) {
        Prices topOfBook = instruments().getTopOfBook(instrumentId);
        if (topOfBook.ask_size == 0 && topOfBook.bid_size == 0) {
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
     * @param zone is the zone we are in now
     */
    private void setZone(int zone){
        if (zone != thisZone) {
            lastZone = thisZone;
            thisZone = zone;
            if (this.verbosity >= 2){
                log("New zone: "+thisZone+" last zone: "+lastZone);
            }
        }
    }
    private void getZone(Prices p){
        // Could maybe use just one lookup..
        if (netPosition > 0) {
            if (p.bid > fastUpperBand){
                setZone(3);
            } else if (p.bid > fastUpperBand1k && p.bid < fastUpperBand){
                setZone(2);
            } else if (p.bid > fastMiddleBand && p.bid < fastUpperBand1k) {
                setZone(1);
            } else if (p.ask < fastMiddleBand && p.ask > fastLowerBand1k) {
                setZone(-1);
            } else if (p.ask < fastLowerBand1k && p.ask > fastLowerBand) {
                setZone(-2);
            } else if (p.ask < fastLowerBand) {
                setZone(-3);
            }
        } else if (netPosition < 0) {
            if (p.ask < fastLowerBand){
                setZone(-3);
            } else if (p.ask < fastLowerBand1k && p.ask > fastLowerBand){
                setZone(-2);
            } else if (p.ask < fastMiddleBand && p.ask > fastLowerBand1k) {
                setZone(-1);
            } else if (p.bid > fastMiddleBand && p.bid < fastUpperBand1k) {
                setZone(1);
            } else if (p.bid > fastUpperBand1k && p.bid < fastUpperBand) {
                setZone(2);
            } else if (p.bid > fastUpperBand) {
                setZone(3);
            }
        }
    }
    /**
     * @param orderId returns true if this order Id is in the map
     */
    private boolean isLive(long orderId){
        if (orderMap.containsKey(orderId)){
            return orderMap.get(orderId).isLive();
        } else {
            return false;
        }
    }
}
