import com.optionscity.freeway.api.*;
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
    // list of ticks to coalesce time frames
    List<Tick> ticks = new ArrayList<>();
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

    // Regression
    SimpleLinearRegressionIndicator fastSLR;
    SimpleLinearRegressionIndicator midSLR;
    // Constants
    private int MAX_TICK_COUNT = 1000;

    @Override
    public void install(IJobSetup setup) {

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
        //log out the moving averages for now
        if(oneMinuteES.getTickCount() > 0) {
            log("The thirty minute fast SMA is "+ thirtyMinuteFastSMA.getValue(oneMinuteES.getTickCount()-1).toString());
            log("The linear regression of the series is "+fastSLR.getValue(oneMinuteES.getTickCount()-1).toString());
            log(""+Decimal.ZERO);
            if (oneMinuteES.getTickCount() - 1 > 0){
                log(""+fastSLR.getValue(1));
                log(""+Decimal.valueOf(oneMinuteES.getTickCount()-1));
                log(""+fastSLR.getValue(oneMinuteES.getTickCount()-1));
                log("The slope of the linear regression is "+getSlope(Decimal.ZERO,fastSLR.getValue(1),Decimal.valueOf(oneMinuteES.getTickCount()-1),fastSLR.getValue(oneMinuteES.getTickCount()-1)));

            }
            log("The low in the current one minute tick is "+ oneMinuteES.getLastTick().getMinPrice());
            log("The high in the current one minute tick is "+ oneMinuteES.getLastTick().getMaxPrice());
        }
        if(tenMinuteES.getTickCount() > 0) {
            log("The low BBand is "+lowBBand.getValue(tenMinuteES.getTickCount()-1).toString());
            log("The middle BBand is "+middleBBand.getValue(tenMinuteES.getTickCount()-1).toString());
            log("The High BBand is "+upBBand.getValue(tenMinuteES.getTickCount()-1).toString());
            log("The linear regression of the series is "+midSLR.getValue(tenMinuteES.getTickCount()-1).toString());
            log("The low in the current ten minute tick is "+ tenMinuteES.getLastTick().getMinPrice());
            log("The high in the current ten minute tick is "+ tenMinuteES.getLastTick().getMaxPrice());
        }
    }

    public void onSignal(TASignal signal) {
        log("Received a signal from "+signal.sender+" with message: "+signal.message);
    }
    // add ticks to time series
    public void onSignal(TickSignal signal) {
        if (signal.sender.equals("MdToTicks.1")){
            log("Received new tick from "+signal.sender+" open: "+signal.tick.getOpenPrice()+" high: "+signal.tick.getMaxPrice()+" low: "+signal.tick.getMinPrice()+" close: "+signal.tick.getClosePrice()+" volume: "+signal.tick.getVolume());
            ticks.add(signal.tick);
            oneMinuteES.addTick(signal.tick);
        }
        if (signal.sender.equals("MdToTicks.2")){
            log("Received new tick from "+signal.sender+" open: "+signal.tick.getOpenPrice()+" high: "+signal.tick.getMaxPrice()+" low: "+signal.tick.getMinPrice()+" close: "+signal.tick.getClosePrice()+" volume: "+signal.tick.getVolume());
            tenMinuteES.addTick(signal.tick);
        }
    }
    public void loadTimeSeries(){
        // create fast series
        oneMinuteES = new TimeSeries("my_fast_series");
        oneMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
        // create middle series
        tenMinuteES = new TimeSeries("my_mid_series");
        tenMinuteES.setMaximumTickCount(MAX_TICK_COUNT);
    }
    private void loadIndicators(){
        if(oneMinuteES.getTickCount() > 0) {
            //get average price of time series bar
            typicalFast = new TypicalPriceIndicator(oneMinuteES);
            typicalMid = new TypicalPriceIndicator(tenMinuteES);

            // create indicators using average price of bars AKA typicalPrice in qa4j
            thirtyMinuteFastSMA = new SMAIndicator(typicalFast, 30);
            sixtyMinuteFastSMA = new SMAIndicator(typicalFast, 60);

            // Bollinger bands stuff
            avgMid12 = new EMAIndicator(typicalMid, 12);
            sdMid12 = new StandardDeviationIndicator(typicalMid, 12);
            middleBBand = new BollingerBandsMiddleIndicator(avgMid12);
            lowBBand = new BollingerBandsLowerIndicator(middleBBand, sdMid12);
            upBBand = new BollingerBandsUpperIndicator(middleBBand, sdMid12);

            fastSLR = new SimpleLinearRegressionIndicator(thirtyMinuteFastSMA, 30);
            midSLR = new SimpleLinearRegressionIndicator(avgMid12, 12);

            indicatorsLoaded=true;
        }
    }
    private Decimal getSlope(Decimal xOne, Decimal yOne, Decimal xTwo, Decimal yTwo){
        Decimal slope = ((yTwo.minus(yOne))).dividedBy((xTwo.minus(xOne)));
        return slope;
    }
}
