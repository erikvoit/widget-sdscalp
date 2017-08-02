import com.optionscity.freeway.api.AbstractJob;
import com.optionscity.freeway.api.IContainer;
import com.optionscity.freeway.api.IJobSetup;
import eu.verdelhan.ta4j.Tick;
import eu.verdelhan.ta4j.TimeSeries;
import eu.verdelhan.ta4j.indicators.simple.ClosePriceIndicator;
import eu.verdelhan.ta4j.indicators.trackers.SMAIndicator;
import org.joda.time.DateTime;
import com.opencsv.CSVReader;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by evoit on 8/2/2017.
 *
 * parse daily bar file from quandl.com
 * file was in Date, Open, High, Low, Last, Change, Settle, Volume, Previous Day OI format
 * NEED TO CHANGE parse order in loadDayBars() if file format changes!!!!!!!
 *
 */
public class TrendAnalysis extends AbstractJob {
    CSVReader reader;
    List<String[]> dayBars;

    private static final String FILEPATH = "./jobfiles/";
    String dailyBars = "";

    @Override
    public void install(IJobSetup setup) {
        setup.addVariable("useDaily", "Load Daily OHLCV bars from csv file", "boolean", "true");
        setup.addVariable("DailyBars", "Name of daily bar csv file", "String", "CHRIS-CME_ES1.csv");
    }
    @Override
    public void begin(IContainer container) {
        super.begin(container);
        dailyBars = getStringVar("DailyBars");
        TimeSeries esDailyBars = loadDayBars();
        log("Time Series " + esDailyBars.getName() + " loaded with " + esDailyBars.getTickCount() + " bars");

        ClosePriceIndicator closePrice = new ClosePriceIndicator(esDailyBars);
        // Getting the simple moving average (SMA) of the close price over the last 5 ticks
        SMAIndicator shortSma = new SMAIndicator(closePrice, 5);
        log("The 5 day SMA is " + shortSma.getValue(esDailyBars.getTickCount()-1));
        // Getting the Simple moving average (SMA) over the last 200 ticks
        SMAIndicator twoHundredDaySma = new SMAIndicator(closePrice, 200);
        log("The 200 day SMA is " + twoHundredDaySma.getValue(esDailyBars.getTickCount()-1));
    }

    public TimeSeries loadDayBars() {
        List<Tick> ticks = new ArrayList<>();

        try{
            reader = new CSVReader(new FileReader(FILEPATH+dailyBars));
        } catch (FileNotFoundException e) {
            log("File not found " + e.toString());
        }
        try{
            dayBars = reader.readAll();
            dayBars.remove(0); // remove header
            Collections.reverse(dayBars);
            log("Loading daily bars from " + FILEPATH+dailyBars);
        }catch (IOException e){
            log("IO Exception "+ e);
        }
        for (String[] bar : dayBars){
            // parse file that was in Date, Open, High, Low, Last, Change, Settle, Volume, Previous Day OI format
            DateTime barDate = DateTime.parse(bar[0]);
            String openPrice = bar[1];
            String highPrice = bar[2];
            String lowPrice = bar[3];
            String closePrice = bar[6];
            String volume = bar[7];

            // add tick to ticks Array list for time series creation
            ticks.add(new Tick(barDate, openPrice, highPrice, lowPrice,closePrice, volume ));

            StringBuilder sb = new StringBuilder();
            sb.append(bar[0]);
            sb.append(",");
            sb.append(openPrice);
            sb.append(",");
            sb.append(highPrice);
            sb.append(",");
            sb.append(lowPrice);
            sb.append(",");
            sb.append(closePrice);
            sb.append(",");
            sb.append(volume);
            sb.append(",");
            log(sb.toString());
        }
        // return a time series from daily bars imported from CSV :)
        return new TimeSeries("ES Continuous Day Bars", ticks);
    }

}
