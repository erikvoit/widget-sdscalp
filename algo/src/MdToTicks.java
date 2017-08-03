import com.optionscity.freeway.api.AbstractJob;
import com.optionscity.freeway.api.IContainer;
import com.optionscity.freeway.api.IJobSetup;
import com.optionscity.freeway.api.Prices;
import com.optionscity.freeway.api.helpers.TimeInterval;
import com.optionscity.freeway.api.messages.MarketBidAskMessage;
import com.optionscity.freeway.api.messages.MarketLastMessage;
import common.sdscalp.TickSignal;
import eu.verdelhan.ta4j.Tick;
import eu.verdelhan.ta4j.TimeSeries;
import org.joda.time.DateTime;
import org.joda.time.Instant;
import org.joda.time.LocalTime;
import org.joda.time.Period;


//TODO implement Quartz for timing bars

/**
 * Created by evoit on 8/2/2017.
 */
public class MdToTicks extends AbstractJob {
    // Job Configs
    private String instrumentId ="";
    LocalTime marketOpenTime;
    private boolean useLast;
    private boolean useTop;
    private int barTimer=60;

    // tick component values
    private double openPrice = Double.NaN;
    private double highPrice = 0;
    private double lowPrice = 0;
    private double closePrice = 0;
    private double volume = 0;
    TimeSeries series;

    private String startTime;
    private String endTime;

    // CONSTANTS
    private TimeInterval timeInterval;
    private int MAX_TICK_COUNT = 1000;

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("Instrument", "Instrument to process live tick data for", "instrumentId", "ES-20170915-F");
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
        //get instrumentId to create live ticks for
        instrumentId=container.getVariable("Instrument");
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

        //TODO use Start End Time for something
        loadStartEndTimeConfigs();
        // load time series
        loadTimeSeries();
        // start adding bars to time series
        //TODO implement onBarTimer let's just get this working with built in timer for now...
        onBarTimer(marketOpenTime, barTimer);
    }

    // Use onTimer to push bars for now...
    public void onTimer(){
        // TODO improve last/mid-market logic on closePrice.
        closePrice=getCleanMidMarketPrice(instrumentId);
        if (Double.isNaN(closePrice)){
            closePrice=instruments().getMarketPrices(instrumentId).last;
        }
        Tick newTick = new Tick(DateTime.now(), openPrice, highPrice, lowPrice, closePrice, volume);
        // send tick to all interested listeners...
        container.signal(new TickSignal(newTick));
        // add to local time series too for now...
        series.addTick(newTick);
        // log tick so we can see something is happening
        log("~~New Tick Recorded to Time Series~~");
        log("Tick end Time "+series.getLastTick().getEndTime()+" close price "+series.getLastTick().getClosePrice());
        // reset for next bar;
        volume = 0;
        openPrice = closePrice;
        highPrice = closePrice;
        lowPrice = closePrice;
    }

    //TODO improve event handlers to consider bid ask when configured
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
        //TODO add top of book updates to tick
    }

    /**
     * Create a moving time series
     */
    public void loadTimeSeries(){
        series = new TimeSeries("my_live_series", Period.seconds(barTimer));
        series.setMaximumTickCount(MAX_TICK_COUNT);

        if (series == null) {
            log("The time series is empty...");
        }else {
            log("The time series has been initialized...");
        }
    }
    /**
     * adds a new tick to the time series.
     * all this does for now is initialize the first tick...
     */
    public void onBarTimer(LocalTime MarketOpen, int timeFrame) {
        //TODO whenever job starts first tick is rounded to nearest interval.  all sequential ticks are length of timeFrame
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
        } else {
            //TODO implement my own timer logic eventually perhaps using Quartz...
        }
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
}
