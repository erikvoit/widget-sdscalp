import com.opencsv.CSVReader;
import com.optionscity.freeway.api.AbstractJob;
import com.optionscity.freeway.api.IContainer;
import com.optionscity.freeway.api.IJobSetup;
import org.joda.time.DateTime;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Created by evoit on 8/2/2017.
 *
 * parse file that was in Date, Open, High, Low, Last, Change, Settle, Volume, Previous Day OI format
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
        //private static final DateFormat DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        loadDayBars();
    }

    public void loadDayBars() {
        try{
            reader = new CSVReader(new FileReader(FILEPATH+dailyBars));
        } catch (FileNotFoundException e) {
            log("File not found " + e.toString());
        }
        try{
            dayBars = reader.readAll();
            dayBars.remove(0); // remove header
            Collections.reverse(dayBars);
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
    }

}
