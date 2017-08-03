import com.optionscity.freeway.api.*;
import com.optionscity.freeway.api.helpers.TimeInterval;
import common.sdscalp.StandardDeviation;
import common.sdscalp.TASignal;
import common.sdscalp.TickSignal;
import eu.verdelhan.ta4j.TimeSeries;
import eu.verdelhan.ta4j.indicators.simple.ClosePriceIndicator;
import eu.verdelhan.ta4j.indicators.simple.TypicalPriceIndicator;
import eu.verdelhan.ta4j.indicators.statistics.StandardDeviationIndicator;
import eu.verdelhan.ta4j.indicators.trackers.EMAIndicator;
import eu.verdelhan.ta4j.indicators.trackers.SMAIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsLowerIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsMiddleIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsUpperIndicator;
import org.joda.time.Period;
import org.jquantlib.math.statistics.GeneralStatistics;

/**
 * Created by evoit on 7/31/2017.
 */
public class SDScalp extends AbstractJob {
    // variables
    final double[] fiveMinPriceArrayTestES = {2469.50, 2468.25, 2467.50, 2465.25, 2467.25, 2465.25, 2463.75, 2461.25, 2460.00, 2462.25, 2464.25, 2465.50, 2467.25, 2468.25, 2469.50, 2472.50, 2474.50, 2474.75, 2476.25, 2478.75};
    GeneralStatistics fiveMinPrices;
    StandardDeviation fiveMinuteStdDiv;

    // ta4lib time series and data structures
    TimeSeries tenSecondES;
    TimeSeries thirtySecondES;
    TimeSeries fiveMinuteES;
    //use (Max+Min+Close)/3
    TypicalPriceIndicator typicalFast;
    TypicalPriceIndicator typicalMid;
    TypicalPriceIndicator typicalSlow;
    //fast
    SMAIndicator thirtySecondFastSMA;
    SMAIndicator oneMinuteFastSMA;
    SMAIndicator fiveMinuteFastSMA;
    SMAIndicator tenMinuteFastSMA;
    //Mid
    SMAIndicator fiveMinuteMidSMA;
    SMAIndicator tenMinuteMidSMA;
    SMAIndicator thirtyMinuteMidSMA;
    //slow
    SMAIndicator fifteenMinuteSlowSMA;
    SMAIndicator thirtyMinuteSlowSMA;
    SMAIndicator sixtyMinuteSlowSMA;
    // BB stuff
    EMAIndicator avgFast12;
    StandardDeviationIndicator sdFast12;
    BollingerBandsMiddleIndicator middleBBand;
    BollingerBandsLowerIndicator lowBBand;
    BollingerBandsUpperIndicator upBBand;

    // Constants
    private int MAX_TICK_COUNT = 1000;

    @Override
    public void install(IJobSetup setup) {

    }

    @Override
    public void begin(IContainer container) {
        super.begin(container);
        container.subscribeToSignals();

        fiveMinuteStdDiv = new StandardDeviation();
        fiveMinuteStdDiv.addSequence(fiveMinPriceArrayTestES);
        log("The Standard deviation calculated by my extended class is " + fiveMinuteStdDiv.standardDeviation());
        // initialize the time series
        loadTimeSeries();
        loadIndicators();
    }
    public void onTimer(){
        //log out the moving averages for now
        log("The thirty second fast SMA is "+thirtySecondFastSMA.getValue(tenSecondES.getTickCount()-1).toString());
        log("The one minute fast SMA is "+oneMinuteFastSMA.getValue(tenSecondES.getTickCount()-1).toString());
        log("The five minute fast SMA is "+fiveMinuteFastSMA.getValue(tenSecondES.getTickCount()-1).toString());
        log("The ten minute fast SMA is "+tenMinuteFastSMA.getValue(tenSecondES.getTickCount()-1).toString());
        // a mid to confirm to confirm time series is working
        log("The five minute mid SMA is "+fiveMinuteMidSMA.getValue(thirtySecondES.getTickCount()-1).toString());
        log("The middle BBand is "+middleBBand.getValue(tenSecondES.getTickCount()-1).toString());
        log("The low BBand is "+lowBBand.getValue(tenSecondES.getTickCount()-1).toString());
        log("The High BBand is "+upBBand.getValue(tenSecondES.getTickCount()-1).toString());
    }
    public void onSignal(TASignal signal) {
        log("Received a signal from "+signal.sender+" with message: "+signal.message);
    }
    // add ticks to time series
    public void onSignal(TickSignal signal) {
        log("Received new tick from "+signal.sender+" open: "+signal.tick.getOpenPrice()+" high: "+signal.tick.getMaxPrice()+" low: "+signal.tick.getMinPrice()+" close: "+signal.tick.getClosePrice()+" volume: "+signal.tick.getVolume());
        tenSecondES.addTick(signal.tick);
        thirtySecondES.addTick(signal.tick);
        fiveMinuteES.addTick(signal.tick);
    }
    public void loadTimeSeries(){
        // create fast series
        tenSecondES = new TimeSeries("my_live_series", Period.seconds(10));
        tenSecondES.setMaximumTickCount(MAX_TICK_COUNT);
        // create middle series
        thirtySecondES = new TimeSeries("my_live_series", Period.minutes(1));
        thirtySecondES.setMaximumTickCount(MAX_TICK_COUNT);
        // create slow series
        fiveMinuteES = new TimeSeries("my_live_series", Period.minutes(5));
        fiveMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
    }
    private void loadIndicators(){
        //get average price of time series bar
        typicalFast = new TypicalPriceIndicator(tenSecondES);
        typicalMid = new TypicalPriceIndicator(thirtySecondES);
        typicalSlow = new TypicalPriceIndicator(fiveMinuteES);

        // create indicators using average price of bars AKA typicalPrice in qa4j
        thirtySecondFastSMA = new SMAIndicator(typicalFast, 3);
        oneMinuteFastSMA = new SMAIndicator(typicalFast, 6);
        fiveMinuteFastSMA = new SMAIndicator(typicalFast, 30);
        tenMinuteFastSMA = new SMAIndicator(typicalFast, 60);
        // a mid to confirm one ticker plant can service all time series hopefully
        fiveMinuteMidSMA = new SMAIndicator(typicalMid, 10);

        // Bollinger bands stuff
        avgFast12 = new EMAIndicator(typicalFast, 12);
        sdFast12 = new StandardDeviationIndicator(typicalFast, 12);
        middleBBand = new BollingerBandsMiddleIndicator(avgFast12);
        lowBBand = new BollingerBandsLowerIndicator(middleBBand, sdFast12);
        upBBand = new BollingerBandsUpperIndicator(middleBBand, sdFast12);
    }
}
