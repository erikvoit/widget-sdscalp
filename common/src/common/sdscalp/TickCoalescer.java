package common.sdscalp;

import eu.verdelhan.ta4j.Decimal;
import eu.verdelhan.ta4j.Tick;
import eu.verdelhan.ta4j.TimeSeries;
import org.joda.time.DateTime;

import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;

/**
 * Created by evoit on 8/3/2017.
 */

//TODO Figure out how to get coalasced tick back into SDScalp
public class TickCoalescer extends TimerTask {
        int numberOfTicks;
        TimeSeries timeSeries;
        Decimal volume;
        List<Tick> ticks = new ArrayList<>();
        Tick coalescedTick;

    public TickCoalescer(int numberOfTicks, List<Tick> ticks,  TimeSeries timeSeries){
        this.numberOfTicks=numberOfTicks;
        this.ticks=ticks;
        this.timeSeries=timeSeries;

    }

    @Override
    public void run() {
        coalascedTick();
    }

    public TimeSeries coalascedTick() {
        Tick openTick = ticks.get(ticks.size()-(numberOfTicks+1));
        Decimal openPrice=openTick.getOpenPrice();
        Tick closeTick = ticks.get(ticks.size()-1);
        Decimal closePrice = closeTick.getClosePrice();
        Decimal highPrice=openPrice;
        Decimal lowPrice =openPrice;

        for (int i=numberOfTicks; (ticks.size()-i) < ticks.size(); i--){
            Tick thisTick=ticks.get(ticks.size()-(i+1));
            volume = volume.plus(thisTick.getVolume());
            //compare this tick's low and high price to what is already in the coalesced tick
            Decimal newLow = lowPrice.minus(thisTick.getMinPrice());
            Decimal newHigh = highPrice.minus(thisTick.getMaxPrice());
            if (newLow.isNegative()) {
                lowPrice = thisTick.getMinPrice();
            }
            if (newHigh.isNegative()){
                highPrice = thisTick.getMaxPrice();
            }
        }
        coalescedTick = new Tick(DateTime.now(), openPrice, highPrice, lowPrice, closePrice, volume);
        timeSeries.addTick(coalescedTick);
        //reset volume
        volume =  null;

        return timeSeries;
    }

}
