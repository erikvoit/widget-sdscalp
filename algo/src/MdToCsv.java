import com.optionscity.freeway.api.*;
import com.optionscity.freeway.api.messages.BookDepthMessage;
import com.optionscity.freeway.api.messages.MarketLastMessage;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;

/**
 * Created by evoit on 8/1/2017.
 *
 * This job logs book depth and or last trade messages to a file in our csv format saved in the jobfiles directory.
 * The job uses onTimer to coalesce book depth updates.
 * Job can be configured to capture all onTrade events or coalesce these as well by setting realTime to false in the job configuration.
 *
 * My intention is not only to hav ea means to record market data in non binary format but also to use this as a helper job
 * to seed data into ta4j technical analysis library as time series data.
 */
public class MdToCsv extends AbstractJob {

    // Variable decelerations
    boolean recLast;
    boolean recDepth;
    boolean writeToFile;
    boolean realTime;
    int depthLevels;

    // File IO variables
    String depthMessage="";
    String oldDepthMessage="";
    String lastMessage="";
    String oldLastMessage="";
    private static final String FILEPATH = "./jobfiles/";
    String fileName="";
    FileWriter fw = null;
    BufferedWriter bw = null;

    @Override
    public void install(IJobSetup setup) {
        //setup.addVariable("Instrument","instrument to record", "instrument", "ES-20171215-F");
        setup.addVariable("Instruments", "Record up to 32 instruments", "instruments", "ES;F;;;DEC17;0;;");
        setup.addVariable("RecordLast", "Record Last Trade Messages", "boolean", "false");
        setup.addVariable("RecordDepth", "Record Book Depth Messages", "boolean", "true");
        setup.addVariable("DepthLevels", "Number of Book Depth Levels to record", "int", "5");
        setup.addVariable("RealTime", "Only if critical! better to use onTimer to coalesce updates", "boolean", "false");
        setup.addVariable("WriteToFile", "Record data to file", "boolean", "true");
        setup.addVariable("FileName", "Name of csv file", "String", "marketdatafile");
        setup.setDefaultDescription("Records Market Data to CSV as Time Series");
    }

    @Override
    public void begin(IContainer container) {
        super.begin(container);

        recLast = getBooleanVar("RecordLast");
        recDepth = getBooleanVar("RecordDepth");
        writeToFile = getBooleanVar("WriteToFile");
        depthLevels = getIntVar("DepthLevels");
        fileName = getStringVar("FileName");
        realTime = getBooleanVar("RealTime");
        container.subscribeToMarketLastMessages();
        container.subscribeToBookDepthMessages();
        container.filterMarketMessages(getStringVar("Instruments"));
        //instrumentIds = instruments().getInstrumentIds();
    }

    public void onMarketLast(MarketLastMessage msg) {
        if (recLast) {
            log("~~New MarketLastMessage~~");
            StringBuilder sb = new StringBuilder();
            sb.append("L,");
            sb.append(System.currentTimeMillis());
            sb.append(",");
            sb.append(msg.instrumentId);
            sb.append(",");
            sb.append(msg.quantity);
            sb.append(",");
            sb.append(msg.price);
            String csvFormat = sb.toString();
            log(""+csvFormat);
            lastMessage=csvFormat;
            if (realTime){
                if (writeToFile){
                    try (BufferedWriter bw = new BufferedWriter(new FileWriter(FILEPATH + fileName, true))) {
                        bw.write(csvFormat+"\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    public void onBookDepth(BookDepthMessage m) {
        if (recDepth) {
        // StringBuilder to build CSV format book depth
        // to be used for time series creation
        StringBuilder sb = new StringBuilder();
        log("~~New BookDepth Msg~~");
        sb.append("B,");
        sb.append(System.currentTimeMillis());
        sb.append(",");
        sb.append(m.instrumentId);
        sb.append(",");
        sb.append(depthLevels);
        sb.append(",");
        Book book = instruments().getBook(m.instrumentId);
        for (int i=0; i < depthLevels; i++){
            sb.append(book.bid[i].quantity);
            sb.append(",");
            sb.append(book.bid[i].price);
            sb.append(",");
        }
        sb.append(depthLevels);
        for (int i=0; i < depthLevels; i++){
            sb.append(",");
            sb.append(book.ask[i].quantity);
            sb.append(",");
            sb.append(book.ask[i].price);
        }
        String csvFormat = sb.toString();
        log(""+csvFormat);
        depthMessage = csvFormat;
        }
    }
    public void onTimer() {
        // Log updates to csv file defined by fileName if writeToFile is True
        if (writeToFile){
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(FILEPATH + fileName, true))) {
                if (recDepth){
                    if (!depthMessage.equals(oldDepthMessage)){
                        bw.write(depthMessage+"\n");
                    }
                }
                if (!realTime){
                    if (!lastMessage.equals(oldLastMessage)) {
                        //logic to adjust timestamp on lastTrade if it happens to fall before the last book update
                        // shouldn't ever move trade more than a few milliseconds
                        // TODO think of a better way to handle this scenario
                        if (recDepth){
                            String[] depthStamp = depthMessage.split(",");
                            String[] lastStamp = lastMessage.split(",");
                            if (Long.parseLong(depthStamp[1]) > Long.parseLong(lastStamp[1])){
                                StringBuilder sb = new StringBuilder();
                                sb.append(lastStamp[0]);
                                sb.append(",");
                                sb.append(depthStamp[1]);
                                sb.append(",");
                                sb.append(lastStamp[2]);
                                sb.append(",");
                                sb.append(lastStamp[3]);
                                sb.append(",");
                                sb.append(lastStamp[4]);
                                String moveLast = sb.toString();
                                bw.write(moveLast+"\n");
                            }else {
                                bw.write(lastMessage + "\n");
                            }
                        }else {
                            bw.write(lastMessage + "\n");
                        }
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            oldLastMessage = lastMessage;
            oldDepthMessage = depthMessage;
        }
    }
}
