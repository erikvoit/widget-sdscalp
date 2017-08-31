import com.optionscity.freeway.api.*;
import common.sdscalp.BollingerBandSignal;
import common.sdscalp.TASignal;
import common.sdscalp.TickSignal;
import eu.verdelhan.ta4j.Decimal;
import eu.verdelhan.ta4j.TimeSeries;
import eu.verdelhan.ta4j.indicators.simple.TypicalPriceIndicator;
import eu.verdelhan.ta4j.indicators.statistics.SimpleLinearRegressionIndicator;
import eu.verdelhan.ta4j.indicators.statistics.StandardDeviationIndicator;
import eu.verdelhan.ta4j.indicators.trackers.EMAIndicator;
import eu.verdelhan.ta4j.indicators.trackers.SMAIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsLowerIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsMiddleIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsUpperIndicator;

/**
 * Created by evoit on 7/31/2017.
 */
public class SDScalp extends AbstractJob {
    String mdToTicksJobName;
    int period;

    // variables
    boolean indicatorsLoaded=false;
    boolean lastTickPartial = false;
    boolean firstTickSeen = true;
    boolean usePartialTicks = true;

    // ta4lib time series and data structures
    TimeSeries timeSeries;
    //use (Max+Min+Close)/3
    TypicalPriceIndicator typicalPriceIndicator;

    // Fast BB
    EMAIndicator fastEMA20;
    StandardDeviationIndicator sdFast20;
    BollingerBandsMiddleIndicator fastMiddleBBand;
    BollingerBandsLowerIndicator fastLowBBand;
    BollingerBandsUpperIndicator fastUpBBand;
    BollingerBandsLowerIndicator fastLowBBand1k;
    BollingerBandsUpperIndicator fastUpBBand1k;
    BollingerBandsLowerIndicator fastLowBBandHalfk;
    BollingerBandsUpperIndicator fastUpBBandHalfk;
    BollingerBandsLowerIndicator fastLowBBand3k;
    BollingerBandsUpperIndicator fastUpBBand3k;

    //Fast
    SMAIndicator thirtyMinuteFastSMA;
    // Regression
    SimpleLinearRegressionIndicator fastSLR;

    // Constants
    private int MAX_TICK_COUNT = 1000;
    private String KEY_NAME = "lastTicks";

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("Load Time Series From DB", "Initialize time series from IDB", "boolean", "true");
        setup.addVariable("Tick Database", "Name of IDB database to retrieve fast time series from", "String", "esoneminute");
        setup.addVariable("Fast Sender", "Name of job to retrieve fast ticks series from", "String", "MdToTicks.1");
        setup.addVariable("Use Partial Ticks", "Set to true if the MdToTicks job sends partial ticks", "boolean", "true");
        setup.addVariable("Periods", "Configure indicator to calculate on this many periods", "int", "20");
    }

    @Override
    public void begin(IContainer container) {
        super.begin(container);
        initialize();  // initialize all job variables
        loadTimeSeries(); // initialize the time series
    }
    public void onTimer()
    {
        if(!indicatorsLoaded){
            loadIndicators();
        }
    }
    public void onSignal(TASignal signal)
    {
        log("Received a signal from "+signal.sender+" with message: "+signal.message);
    }
    // add ticks to time series
    public void onSignal(TickSignal signal)
    {
        if (signal.sender.equals(mdToTicksJobName))
        {
            log("Received new tick from "+signal.sender+" open: "+signal.tick.getOpenPrice()+" high: "+signal.tick.getMaxPrice()+" low: "+signal.tick.getMinPrice()+" close: "+signal.tick.getClosePrice()+" volume: "+signal.tick.getVolume());
            if (this.firstTickSeen)
            {
                if (signal.partial)
                {
                    log("this is a partial tick");
                    log("last tick was flagged as partial so dropping last tick");
                    log("adding new partial tick...");
                    this.timeSeries.addTick(signal.tick);
                    this.lastTickPartial = true;
                } else
                {
                    log("this is a full tick");
                    this.timeSeries.addTick(signal.tick);
                    this.lastTickPartial = false;
                }
                this.firstTickSeen = false; // after receiving the first tick we can now start deleting partial ticks.
            } else { // this is not the first tick we are processing...
                if (signal.partial){
                    log("this is a partial tick");
                    if (this.lastTickPartial){
                        log("last tick was flagged as partial so dropping last tick");
                        this.timeSeries.dropLastTick();
                    }
                    log("adding new partial tick...");
                    this.timeSeries.addTick(signal.tick);
                    this.lastTickPartial = true;
                } else {
                    log("this is a full tick");
                    if (usePartialTicks){
                        this.timeSeries.dropLastTick();
                    }
                    this.timeSeries.addTick(signal.tick);
                    this.lastTickPartial = false;
                }
                if(this.timeSeries.getTickCount()-1 > 0) {
                    log("There are "+(this.timeSeries.getTickCount()-1)+" ticks in the one minute series");
                    // Send fast Bollinger band values as a signal
                    log("The fast lower band is at "+ fastLowBBand.getValue(timeSeries.getTickCount()-1).toString());
                    log("The fast middle band is at "+ fastMiddleBBand.getValue(timeSeries.getTickCount()-1).toString());
                    log("The fast upper band is at "+ fastUpBBand.getValue(timeSeries.getTickCount()-1).toString());
                    log("The bandwidth is currently "+(fastUpBBand.getValue(timeSeries.getTickCount()-1).minus(fastLowBBand.getValue(timeSeries.getTickCount()-1))));
                    if (this.timeSeries.getTickCount() - 1 > 0){
                        log("The slope of the linear regression is "+getSlope(Decimal.ZERO,fastSLR.getValue(1),Decimal.valueOf(timeSeries.getTickCount()-1),fastSLR.getValue(timeSeries.getTickCount()-1)));
                    }
                    // Send Bollinger band update to execution job
                    bbSignal();
                }
            }
        }
    }
    /**
     * Attempts to load time series data from IDB otherwise creates new series
     */
    public void initialize()
    {
        container.subscribeToSignals();
        mdToTicksJobName =getStringVar("Fast Sender");
        period = getIntVar("Periods");
        usePartialTicks = getBooleanVar("Use Partial Ticks");
    }
    /**
     * Attempts to load time series data from IDB otherwise creates new series
     */
    public void loadTimeSeries(){
        if (getBooleanVar("Load Time Series From DB")){
            // create fast series
            IDB tickDb=container.getDB(container.getVariable("Tick Database"));
            if (tickDb != null){
                this.timeSeries = (TimeSeries) tickDb.get(KEY_NAME);
                log("Loading Time Series from DB");
                log("There are "+ timeSeries.getTickCount()+ " ticks in the series");
                this.timeSeries.setMaximumTickCount(MAX_TICK_COUNT);
                if (usePartialTicks){
                    this.timeSeries.setNoCache(true);
                }
            } else {
                this.timeSeries = new TimeSeries("my_fast_series");
                timeSeries.setMaximumTickCount(MAX_TICK_COUNT);
                if (usePartialTicks){
                    timeSeries.setNoCache(true);
                }
            }
        }else{
            this.timeSeries = new TimeSeries("my_fast_series");
            this.timeSeries.setMaximumTickCount(MAX_TICK_COUNT);
            if (usePartialTicks){
                timeSeries.setNoCache(true);
            }
        }
    }
    /**
     * Instaniates all time series indicators once time series are created
     */
    private void loadIndicators(){
        if(this.timeSeries.getTickCount()-1 > 0) {
            //get average price of time series bar
            this.typicalPriceIndicator = new TypicalPriceIndicator(this.timeSeries);

            // create indicators using average price of bars AKA typicalPrice in qa4j
            thirtyMinuteFastSMA = new SMAIndicator(typicalPriceIndicator, 30);

            //Bollinger bands
            this.fastEMA20 = new EMAIndicator(this.typicalPriceIndicator, this.period);
            this.sdFast20 = new StandardDeviationIndicator(this.typicalPriceIndicator, this.period);
            this.fastMiddleBBand = new BollingerBandsMiddleIndicator(this.fastEMA20);
            this.fastLowBBand = new BollingerBandsLowerIndicator(this.fastMiddleBBand, sdFast20);
            this.fastUpBBand = new BollingerBandsUpperIndicator(this.fastMiddleBBand, sdFast20);
            this.fastLowBBand1k = new BollingerBandsLowerIndicator(this.fastMiddleBBand, sdFast20, Decimal.ONE);
            this.fastUpBBand1k = new BollingerBandsUpperIndicator(this.fastMiddleBBand, sdFast20, Decimal.ONE);
            this.fastLowBBand3k = new BollingerBandsLowerIndicator(this.fastMiddleBBand, sdFast20, Decimal.THREE);
            this.fastUpBBand3k = new BollingerBandsUpperIndicator(this.fastMiddleBBand, sdFast20, Decimal.THREE);
            this.fastLowBBandHalfk = new BollingerBandsLowerIndicator(this.fastMiddleBBand, sdFast20, Decimal.valueOf(.5));
            this.fastUpBBandHalfk = new BollingerBandsUpperIndicator(this.fastMiddleBBand, sdFast20, Decimal.valueOf(.5));


            fastSLR = new SimpleLinearRegressionIndicator(this.thirtyMinuteFastSMA, this.period);

            this.indicatorsLoaded=true;
        } else {
            log("Waiting for sufficient tick data to load indicators...");
        }
    }
    /**
     * sends relevant Bollinger Band updates to subscribed listeners
     */
    private void bbSignal(){
        double lBand=fastLowBBand.getValue(timeSeries.getTickCount()-1).toDouble();
        double mBand=fastMiddleBBand.getValue(timeSeries.getTickCount()-1).toDouble();
        double hBand=fastUpBBand.getValue(timeSeries.getTickCount()-1).toDouble();
        double bWidth=fastUpBBand.getValue(timeSeries.getTickCount()-1).minus(fastLowBBand.getValue(timeSeries.getTickCount()-1)).toDouble();
        double lBand1k=fastLowBBand1k.getValue(timeSeries.getTickCount()-1).toDouble();
        double hBand1k=fastUpBBand1k.getValue(timeSeries.getTickCount()-1).toDouble();
        double lBand3k=fastLowBBand3k.getValue(timeSeries.getTickCount()-1).toDouble();
        double hBand3k=fastUpBBand3k.getValue(timeSeries.getTickCount()-1).toDouble();
        double lBandHk=fastLowBBandHalfk.getValue(timeSeries.getTickCount()-1).toDouble();
        double hBandHk=fastUpBBandHalfk.getValue(timeSeries.getTickCount()-1).toDouble();
        container.signal(new BollingerBandSignal(lBand,mBand,hBand,bWidth,lBand1k,hBand1k,lBand3k,hBand3k,lBandHk,hBandHk));
    }
    /**
     * Calculates the slope of two points in a linear regression
     *
     * @param xOne Decimal value of first point in linear regression
     * @param yOne Decimal value of the Y coordinate in underlying terms
     * @param xTwo Decimal value of the most recent point in the series
     * @param yTwo Decimal value of the most recent Y coordinate in SLR
     *
     * @return slope of the two point in the linear regression
     *
     */
    private Decimal getSlope(Decimal xOne, Decimal yOne, Decimal xTwo, Decimal yTwo){
        Decimal slope = ((yTwo.minus(yOne))).dividedBy((xTwo.minus(xOne)));
        return slope;
    }
}
