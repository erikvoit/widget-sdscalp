import com.optionscity.freeway.api.*;
import com.optionscity.freeway.api.helpers.TimeInterval;
import com.optionscity.freeway.api.messages.MarketBidAskMessage;
import com.optionscity.freeway.api.messages.MarketLastMessage;
import common.sdscalp.TickSignal;
import eu.verdelhan.ta4j.Tick;
import eu.verdelhan.ta4j.TimeSeries;
import org.joda.time.DateTime;
import org.joda.time.Instant;
import org.joda.time.LocalTime;


/**
 * Created by evoit on 8/1/2017.
 *
 * Creates ta4j Tick objects from live market data in (joda time Instant, open, high, low, close, volume) format.
 * Sends ticks to to any interested job via TickSignal. a tick duration is currently controlled by onTimer()
 *
 * Job should be started at market open by controller job
 *
 * TODO backup ticks to file / Send all available ticks to interested listeners to prime technical indicators.
 * TODO consider configuring all time frames in single MDtoTicks ticker plant.
 *
 */

public class MdToTicks extends AbstractJob {
    // Job Configs
    private String instrumentId ="";
    private LocalTime marketOpenTime;
    private boolean useLast;
    private boolean useTop;
    private int barTimer=60;
    IDB tickDB;

    // tick component values
    private double openPrice = Double.NaN;
    private double highPrice = 0;
    private double lowPrice = 0;
    private double closePrice = 0;
    private double volume = 0;
    TimeSeries series;

    private String startTime;
    private String endTime;

    // Constants
    private int MAX_TICK_COUNT = 1000;
    private TimeInterval timeInterval;

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("Instrument", "Instrument to process live tick data for", "instrumentId", "ES-20170915-F");
        setup.addVariable("Database", "Name of IDB database to store ticks to", "String", "tickDB");
        setup.addVariable("Start Time", "Time after which the job can run; in hh:mm (in server time zone)", "String", "");
        setup.addVariable("End Time", "Time after which job stops; in hh:mm (in server time zone)", "String", "");
        setup.addVariable("Market Open Time", "Round first interval to this time in hh:mm", "String", "17:00");
        setup.addVariable("Bar Timer", "Duration of a bar/tick in seconds", "int", "60");
        setup.addVariable("useLast", "Use last trade prices to construct tick", "boolean", "true");
        setup.addVariable("useTop", "Use top of book prices to construct tick", "boolean", "false");

    }
    @Override
    public void begin(IContainer container) {
        super.begin(container);
        //instantiate all configurable variables
        initializeTickerPlant();
        //TODO use Start End Time for something
        loadStartEndTimeConfigs();
        // load time series
        loadTimeSeries();
        // start adding bars to time series
        //TODO improve onBarTimer...
        onBarTimer(marketOpenTime, barTimer);
    }
    /**
     * creates a new tick (bar) every cycle and sends to interested listeners
     */
    public void onTimer(){
        // use mid market to generate close price of bar.  If no mid market is available use last price.
        closePrice=getCleanMidMarketPrice(instrumentId);
        if (Double.isNaN(closePrice)){
            closePrice=instruments().getMarketPrices(instrumentId).last;
        }
        // create Tick
        Tick newTick = createTick();
        // send tick to all interested listeners...
        container.signal(new TickSignal(newTick));
        //backup Time Series to a DB
        series.addTick(newTick);
        tickDB.put("lastTicks", series);

        // log tick so we can see something is happening
        log("~~New Tick Recorded to Time Series~~");
        log("Tick end Time "+series.getLastTick().getEndTime()+" close price "+series.getLastTick().getClosePrice());
        // reset Tick variables for next tick
        resetTick();
    }
    /**
     * logs market last, totals volume for Tick, sets high and low prices if useLast
     */
    public void onMarketLast(MarketLastMessage m) {
        volume += m.quantity;
        if (useLast) {
            log("~~New MarketLastMessage~~");
            StringBuilder sb = new StringBuilder();
            sb.append("L,");
            sb.append(System.currentTimeMillis());
            sb.append(",");
            sb.append(m.instrumentId);
            sb.append(",");
            sb.append(m.quantity);
            sb.append(",");
            sb.append(m.price);
            log(sb.toString());
            // logic to build high and low prices of each bar price is reset to bar close on timer...
            if (m.price > highPrice){
                highPrice = m.price;
            }
            if (m.price < lowPrice){
                lowPrice = m.price;
            }
        }
    }
    public void onMarketBidAsk(MarketBidAskMessage m) {
        // If considering top of book updates in tick high low
        if (useTop){
            Prices topOfBook = instruments().getTopOfBook(m.instrumentId);
            if (topOfBook.ask_size != 0 && topOfBook.ask < lowPrice){
                lowPrice = instruments().getTopOfBook(m.instrumentId).ask;
                log("New low price in tick from top of book"+ topOfBook.ask);
            }
            if (topOfBook.bid_size != 0 && topOfBook.bid > highPrice){
                highPrice = instruments().getTopOfBook(m.instrumentId).bid;
                log("New high price in tick from top of book"+ topOfBook.bid);
            }
        }
    }
    /**
     * Loads the 'Start time' and 'End time' configuration and validates the input.
     */
    private void loadStartEndTimeConfigs() {
        startTime = getStringVar("Start time").trim();
        if (startTime.isEmpty()) {
            startTime = "17:00";
        }

        endTime = getStringVar("End time").trim();
        if (endTime.isEmpty()) {
            endTime = "17:00";
        }
        timeInterval = new TimeInterval(startTime, endTime, 1);
    }
    /**
     * instantiates all configurable variables
     */
    private void initializeTickerPlant(){
        instrumentId=container.getVariable("Instrument");
        // assign job configured variable name to Database
        tickDB=container.getDB(container.getVariable("Database"));
        //determine if we will use last prices and/or market bid offer to create ticks
        useLast=getBooleanVar("useLast");
        useTop=getBooleanVar("useTop");

        //timer stuff
        barTimer=getIntVar("Bar Timer");
        marketOpenTime=LocalTime.parse(getStringVar("Market Open Time"));
        //subscribe to Messages
        container.subscribeToMarketLastMessages();
        container.subscribeToMarketBidAskMessages();
        // filter messages for instrumentId job is configured on
        container.filterMarketMessages(instrumentId);
    }
    /**
     * Create a moving time series
     */
    public void loadTimeSeries(){
        series = new TimeSeries("my_live_series");
        series.setMaximumTickCount(MAX_TICK_COUNT);

        if (series == null) {
            log("The time series is empty...");
        }else {
            log("The time series has been initialized...");
        }
    }
    /**
     * all this does for now is initialize the first tick...
     */
    //TODO whenever job starts first tick is rounded to nearest interval.  all sequential ticks are length of timeFrame
    private void onBarTimer(LocalTime MarketOpen, int timeFrame) {
        if (Double.isNaN(openPrice)){
            log("Starting first bar...");
            // mark instant first bar is started
            Instant firstBarStarted = new Instant();
            firstBarStarted.now();

            // initialize first bar
            openPrice=getCleanMidMarketPrice(instrumentId);
            highPrice=openPrice;
            lowPrice=openPrice;
            closePrice=openPrice;
        }
    }
    /**
     * returns a tick object
     */
    private Tick createTick(){
        // create a tick;
        Tick newTick = new Tick(DateTime.now(), openPrice, highPrice, lowPrice, closePrice, volume);
        return newTick;
    }
    /**
     * resets tick variables for next tick
     */
    private void resetTick(){
        // reset for next bar;
        volume = 0;
        openPrice = closePrice;
        highPrice = closePrice;
        lowPrice = closePrice;
    }
    /**
     * get a clean mid market price
     */
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
}
