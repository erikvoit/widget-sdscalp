import com.optionscity.freeway.api.*;
import common.sdscalp.BollingerBandSignal;
import common.sdscalp.StandardDeviation;
import common.sdscalp.TASignal;
import common.sdscalp.TickSignal;
import eu.verdelhan.ta4j.Decimal;
import eu.verdelhan.ta4j.Tick;
import eu.verdelhan.ta4j.TimeSeries;
import eu.verdelhan.ta4j.indicators.simple.TypicalPriceIndicator;
import eu.verdelhan.ta4j.indicators.statistics.SimpleLinearRegressionIndicator;
import eu.verdelhan.ta4j.indicators.statistics.StandardDeviationIndicator;
import eu.verdelhan.ta4j.indicators.trackers.EMAIndicator;
import eu.verdelhan.ta4j.indicators.trackers.SMAIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsLowerIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsMiddleIndicator;
import eu.verdelhan.ta4j.indicators.trackers.bollinger.BollingerBandsUpperIndicator;
import org.joda.time.Period;
import org.jquantlib.math.statistics.GeneralStatistics;

import java.util.*;

/**
 * Created by evoit on 7/31/2017.
 */
public class SDScalp extends AbstractJob {
    // variables
    boolean indicatorsLoaded=false;

    // ta4lib time series and data structures
    TimeSeries oneMinuteES;
    TimeSeries tenMinuteES;
    //use (Max+Min+Close)/3
    TypicalPriceIndicator typicalFast;
    TypicalPriceIndicator typicalMid;
    //Fast
    SMAIndicator thirtyMinuteFastSMA;
    //Slow
    SMAIndicator sixtyMinuteFastSMA;

    // BB stuff
    EMAIndicator avgMid12;
    StandardDeviationIndicator sdMid12;
    BollingerBandsMiddleIndicator middleBBand;
    BollingerBandsLowerIndicator lowBBand;
    BollingerBandsUpperIndicator upBBand;


    // Fast BB
    EMAIndicator fastEMA20;
    StandardDeviationIndicator sdFast20;
    BollingerBandsMiddleIndicator fastMiddleBBand;
    BollingerBandsLowerIndicator fastLowBBand;
    BollingerBandsUpperIndicator fastUpBBand;
    BollingerBandsLowerIndicator fastLowBBand1k;
    BollingerBandsUpperIndicator fastUpBBand1k;

    // Regression
    SimpleLinearRegressionIndicator fastSLR;
    SimpleLinearRegressionIndicator midSLR;
    // Constants
    private int MAX_TICK_COUNT = 1000;
    private String KEY_NAME = "lastTicks";

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("Load Time Series From DB", "Initialize time series from IDB", "boolean", "true");
        setup.addVariable("Fast Tick Database", "Name of IDB database to retrieve fast time series from", "String", "esoneminute");
        setup.addVariable("Slow Tick Database", "Name of IDB database to retrieve slow time series from", "String", "estenminute");
    }

    @Override
    public void begin(IContainer container) {
        super.begin(container);
        container.subscribeToSignals();
        // initialize the time series
        loadTimeSeries();
    }
    public void onTimer(){
        if(!indicatorsLoaded){
            loadIndicators();
        }
    }

    public void onSignal(TASignal signal) {
        log("Received a signal from "+signal.sender+" with message: "+signal.message);
    }
    // add ticks to time series
    public void onSignal(TickSignal signal) {
        if (signal.sender.equals("MdToTicks.1")){
            log("Received new tick from "+signal.sender+" open: "+signal.tick.getOpenPrice()+" high: "+signal.tick.getMaxPrice()+" low: "+signal.tick.getMinPrice()+" close: "+signal.tick.getClosePrice()+" volume: "+signal.tick.getVolume());
            oneMinuteES.addTick(signal.tick);
            if(oneMinuteES.getTickCount() > 0) {
                log("There are "+(oneMinuteES.getTickCount()-1)+" ticks in the one minute series");
                log("The thirty minute fast SMA is "+ thirtyMinuteFastSMA.getValue(oneMinuteES.getTickCount()-1).toString());
                // Send fast Bollinger band values as a signal
                log("The fast lower band is at "+ fastLowBBand.getValue(oneMinuteES.getTickCount()-1).toString());
                log("The fast middle band is at "+ fastMiddleBBand.getValue(oneMinuteES.getTickCount()-1).toString());
                log("The fast upper band is at "+ fastUpBBand.getValue(oneMinuteES.getTickCount()-1).toString());
                log("The bandwidth is currently "+(fastUpBBand.getValue(oneMinuteES.getTickCount()-1).minus(fastLowBBand.getValue(oneMinuteES.getTickCount()-1))));
                if (oneMinuteES.getTickCount() - 1 > 0){
                    log(""+fastSLR.getValue(oneMinuteES.getTickCount()-1));
                    log("The slope of the linear regression is "+getSlope(Decimal.ZERO,fastSLR.getValue(1),Decimal.valueOf(oneMinuteES.getTickCount()-1),fastSLR.getValue(oneMinuteES.getTickCount()-1)));
                }
                // Send Bollinger band update to execution job
                bbSignal();
            }
        }
        if (signal.sender.equals("MdToTicks.2")){
            log("Received new tick from "+signal.sender+" open: "+signal.tick.getOpenPrice()+" high: "+signal.tick.getMaxPrice()+" low: "+signal.tick.getMinPrice()+" close: "+signal.tick.getClosePrice()+" volume: "+signal.tick.getVolume());
            tenMinuteES.addTick(signal.tick);
            if(tenMinuteES.getTickCount() > 0) {
                log("The low BBand is "+lowBBand.getValue(tenMinuteES.getTickCount()-1).toString());
                log("The middle BBand is "+middleBBand.getValue(tenMinuteES.getTickCount()-1).toString());
                log("The High BBand is "+upBBand.getValue(tenMinuteES.getTickCount()-1).toString());
            }
        }
    }
    public void loadTimeSeries(){
        if (getBooleanVar("Load Time Series From DB")){
            // create fast series
            IDB fastTickDb=container.getDB(container.getVariable("Fast Tick Database"));
            if (fastTickDb != null){
                oneMinuteES = (TimeSeries) fastTickDb.get(KEY_NAME);
                log("Loading Time Series from DB");
                log("There are "+oneMinuteES.getTickCount()+ " ticks in the series");
                oneMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
            } else {
                oneMinuteES = new TimeSeries("my_fast_series");
            }
            // create slow series
            IDB slowTickDb=container.getDB(container.getVariable("Slow Tick Database"));
            if (slowTickDb != null){
                tenMinuteES = (TimeSeries) slowTickDb.get(KEY_NAME);
                log("There are "+tenMinuteES.getTickCount()+ " ticks in the series");
                tenMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
            }else{
                tenMinuteES = new TimeSeries("my_mid_series");
                tenMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
            }
        }else{
            oneMinuteES = new TimeSeries("my_fast_series");
            tenMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
            tenMinuteES = new TimeSeries("my_mid_series");
            tenMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
        }
    }
    private void loadIndicators(){
        if(oneMinuteES.getTickCount() > 0) {
            //get average price of time series bar
            typicalFast = new TypicalPriceIndicator(oneMinuteES);
            typicalMid = new TypicalPriceIndicator(tenMinuteES);

            // create indicators using average price of bars AKA typicalPrice in qa4j
            thirtyMinuteFastSMA = new SMAIndicator(typicalFast, 30);
            sixtyMinuteFastSMA = new SMAIndicator(typicalFast, 60);

            //fast Bollinger bands
            fastEMA20 = new EMAIndicator(typicalFast, 20);
            sdFast20 = new StandardDeviationIndicator(typicalFast, 20);
            fastMiddleBBand = new BollingerBandsMiddleIndicator(fastEMA20);
            fastLowBBand = new BollingerBandsLowerIndicator(fastMiddleBBand, sdFast20);
            fastUpBBand = new BollingerBandsUpperIndicator(fastMiddleBBand, sdFast20);
            fastLowBBand1k = new BollingerBandsLowerIndicator(fastMiddleBBand, sdFast20, Decimal.ONE);
            fastUpBBand1k = new BollingerBandsUpperIndicator(fastMiddleBBand, sdFast20, Decimal.ONE);

            // Bollinger bands stuff
            avgMid12 = new EMAIndicator(typicalMid, 20);
            sdMid12 = new StandardDeviationIndicator(typicalMid, 20);
            middleBBand = new BollingerBandsMiddleIndicator(avgMid12);
            lowBBand = new BollingerBandsLowerIndicator(middleBBand, sdMid12);
            upBBand = new BollingerBandsUpperIndicator(middleBBand, sdMid12);

            fastSLR = new SimpleLinearRegressionIndicator(thirtyMinuteFastSMA, 30);
            midSLR = new SimpleLinearRegressionIndicator(avgMid12, 12);

            indicatorsLoaded=true;
        }
    }
    private void bbSignal(){
        double lBand=fastLowBBand.getValue(oneMinuteES.getTickCount()-1).toDouble();
        double mBand=fastMiddleBBand.getValue(oneMinuteES.getTickCount()-1).toDouble();
        double hBand=fastUpBBand.getValue(oneMinuteES.getTickCount()-1).toDouble();
        double bWidth=fastUpBBand.getValue(oneMinuteES.getTickCount()-1).minus(fastLowBBand.getValue(oneMinuteES.getTickCount()-1)).toDouble();
        double lBand1k=fastLowBBand1k.getValue(oneMinuteES.getTickCount()-1).toDouble();
        double hBand1k=fastUpBBand1k.getValue(oneMinuteES.getTickCount()-1).toDouble();
        container.signal(new BollingerBandSignal(lBand,mBand,hBand,bWidth,lBand1k,hBand1k));
    }
    private Decimal getSlope(Decimal xOne, Decimal yOne, Decimal xTwo, Decimal yTwo){
        Decimal slope = ((yTwo.minus(yOne))).dividedBy((xTwo.minus(xOne)));
        return slope;
    }
}
