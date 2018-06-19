package io.sakura.sample;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.CoderRegistry;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Mean;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.transforms.windowing.SlidingWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.joda.time.Duration;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DataflowToBq {
    public static void main(String[] args) {
        Logger logger = LoggerFactory.getLogger(DataflowToBq.class);

        // get bundle options
        logger.debug("get resource bundle");
        ResourceBundle config = ResourceBundle.getBundle("config");
        String subscriptionPath = String.format("projects/%1$s/subscriptions/%2$s",
                config.getString("targetProject"),
                config.getString("targetSubscription"));
        String bqTable = config.getString("targetBigQueryTable");

        // Start by defining the options for the pipeline.
        logger.debug("create dataflow pipeline options");
        DataflowPipelineOptions options = PipelineOptionsFactory.fromArgs(args)
                .withValidation().create().as(DataflowPipelineOptions.class);
        // Set options of dataflow pipeline
        // https://cloud.google.com/dataflow/pipelines/specifying-exec-params
        options.setProject(config.getString("targetProject"));
        options.setTempLocation(config.getString("targetTempLocation"));
        options.setRunner(DataflowRunner.class);
        options.setStreaming(true);
        options.setJobName(config.getString("targetJobName"));
        // for demo only
        options.setNumWorkers(Integer.parseInt(config.getString("targetNumWorkers")));
        options.setMaxNumWorkers(Integer.parseInt(config.getString("targetMaxNumWorkers")));
        options.setDiskSizeGb(Integer.parseInt(config.getString("targetDiskSizeGb")));
        options.setWorkerMachineType(config.getString("targetWorkerMachineType"));

        // Then create the pipeline.
        Pipeline p = Pipeline.create(options);

        // set coder for org.json.JSONObject
        CoderRegistry cr = p.getCoderRegistry();
        cr.registerCoderForClass(JSONObject.class, AvroCoder.of(JSONObject.class));
        cr.registerCoderForClass(MessageMappedBq.class, AvroCoder.of(MessageMappedBq.class));

        // PubSub -> Parse message -> validate data (check sensor spec data range) -> make sliding window
        PCollection<MessageMappedBq> validatedStream = p.apply("ReadFromPubSub", PubsubIO.readMessages().fromSubscription(subscriptionPath))
                .apply(ParDo.of(new SakuraIOChannelsMessageToJson()))
                .apply(ParDo.of(new ConvertMessage()))
                .apply(ParDo.of(new ValidateData()))
                .apply(Window.<MessageMappedBq>into(
                        SlidingWindows.of(Duration.standardMinutes(30))
                                .every(Duration.standardMinutes(5))));

        // average data in window
        PCollectionView<Map<String, Double>> criteriaData = validatedStream
                .apply("get map: serial to data", ParDo.of(new DoFn<MessageMappedBq, KV<String, Double>>() {
                    @ProcessElement
                    public void processElement(ProcessContext c) throws Exception {
                        MessageMappedBq m = c.element();
                        String key = m.getCh1();
                        Double data = m.getCh2();
                        c.output(KV.of(key, data));
                    }
                }))
                .apply("average data on serial number", Mean.<String, Double>perKey())
                .apply("to view", View.<String, Double>asMap());

        validatedStream
                .apply("filter anomalous values", ParDo.of(new FilterData(criteriaData)).withSideInputs(criteriaData))
                .apply("Convert to tablerows format", ParDo.of(new StoreBQ()))
                .apply("WriteToBQ", BigQueryIO.writeTableRows()
                        .to(bqTable) // 必要ならシャーディングする
                        .withSchema(MessageMappedBq.getBqSchema())
                        .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND));
        p.run();
    }

    static class SakuraIOChannelsMessageToJson extends DoFn<PubsubMessage, JSONObject> {
        @ProcessElement
        public void processElement(ProcessContext c) {
            Logger logger = LoggerFactory.getLogger(this.getClass());
            logger.debug("start SakuraIOChannelsMessageToJson.DoFn");
            // Get the input element from ProcessContext.
            PubsubMessage message = (PubsubMessage) c.element();
            logger.debug(message.toString());
            // parse json data
            JSONObject json = new JSONObject(new String(message.getPayload()));
            // only use channels message
            if (!(json.getString("type").equals("channels"))) {
                logger.debug("type: {} is not eq to 'channels'", json.getString("type"));
                return;
            }
            // emit data
            c.output(json);
        }
    }

    static class ConvertMessage extends DoFn<JSONObject, MessageMappedBq> {
        @ProcessElement
        public void processElement(ProcessContext c) {
            Logger logger = LoggerFactory.getLogger(this.getClass());
            logger.debug("start ConvertMessage.DoFn");
            // Get the input element from ProcessContext.
            JSONObject j = (JSONObject) c.element();
            logger.debug(j.toString());
            // build message object from json
            MessageMappedBq m = new MessageMappedBq();
            m.setModule(j.getString("module"));
            m.setDateTime(j.getString("datetime"));
            JSONArray data = j.getJSONObject("payload").getJSONArray("channels");
            int ch;
            for (Object k : data) {
                ch = ((JSONObject) k).getInt("channel");
                switch (ch) {
                    case 0:
                        m.setCh0(((JSONObject) k).getInt("value"));
                        break;
                    case 1:
                        m.setCh1((((JSONObject) k).getBigInteger("value")).toString());
                        break;
                    case 2:
                        m.setCh2(((JSONObject) k).getDouble("value"));
                        break;
                    case 3:
                        m.setCh3(((JSONObject) k).getDouble("value"));
                        break;
                    case 4:
                        m.setCh4(((JSONObject) k).getDouble("value"));
                        break;
                    case 5:
                        try {
                            m.setCh5(((JSONObject) k).getDouble("value"));
                        } catch (Exception e) {
                            // some firmware doesn't send ch5
                            m.setCh5(-1.0);
                        }
                        break;
                }
            }

            // emit data
            c.output(m);
        }
    }

    static class ValidateData extends DoFn<MessageMappedBq, MessageMappedBq> {
        @ProcessElement
        public void processElement(ProcessContext c) {
            Logger logger = LoggerFactory.getLogger(this.getClass());
            logger.debug("start ValidateData.DoFn");
            // Get the input element from ProcessContext
            MessageMappedBq m = (MessageMappedBq) c.element();
            // validation rule 1 : distance sensor must return [300, 9999) values
            Boolean passed = (m.getCh2() >= 300) && (m.getCh2() < 9999);
            if (!passed) { return; }
            // validation rule 2 : serial number must be registered values
            String[] registered_serials = {"2890554957", "2513889910", "2115424132", "4156537390", "3969087005", "824320472"};
            passed = Arrays.asList(registered_serials).contains(m.getCh1());
            if (!passed) { return; }

            // all validation passed
            c.output(m);
        }
    }

    static class FilterData extends DoFn<MessageMappedBq, MessageMappedBq> {
        PCollectionView<Map<String, Double>> criteriaData;

        public FilterData(PCollectionView<Map<String, Double>> avgData) {
            this.criteriaData = avgData;
        }

        @ProcessElement
        public void processElement(ProcessContext c) {
            Logger logger = LoggerFactory.getLogger(this.getClass());
            logger.debug("start FilterData.DoFn");
            // Get the input element from ProcessContext
            MessageMappedBq m = (MessageMappedBq) c.element();
            String serial = m.getCh1();
            // Check whether data is normal or anomalous by using avg
            Double criteria = c.sideInput(this.criteriaData).get(serial);
            if (checkCriteria(m.getCh2(), criteria)) {
                c.output(m);
            }
        }

        private boolean checkCriteria(Double current, Double criteria) {
            return ((Math.abs(current - criteria)) / current) < 0.3;
        }
    }

    static class StoreBQ extends DoFn<MessageMappedBq, TableRow> {
        @ProcessElement
        public void processElement(ProcessContext c) {
            Logger logger = LoggerFactory.getLogger(this.getClass());
            logger.debug("start StoreBQ.DoFn");
            // Get the input element from ProcessContext
            MessageMappedBq m = (MessageMappedBq) c.element();
            // emit data
            c.output(m.makeTableRow());
        }
    }
}

/**
 * module: sakura.io module id
 * dateTime: datetime when data received
 * ch0: counter
 * ch1: Serial Number
 * ch2: distance sensor value
 * ch3: thermal sensor value
 * ch4: not used
 * ch5: battery voltage
 */
class MessageMappedBq {
    private String module;
    private String dateTime;
    private Integer ch0;
    private String ch1;
    private double ch2, ch3, ch4, ch5;

    // TableSchema for big query
    public static TableSchema getBqSchema() {
        List<TableFieldSchema> fields = new ArrayList<>();
        fields.add(new TableFieldSchema().setName("module").setType("STRING"));
        fields.add(new TableFieldSchema().setName("datetime").setType("TIMESTAMP"));
        fields.add(new TableFieldSchema().setName("ch0").setType("INT64"));
        fields.add(new TableFieldSchema().setName("ch1").setType("STRING"));
        fields.add(new TableFieldSchema().setName("ch2").setType("FLOAT64"));
        fields.add(new TableFieldSchema().setName("ch3").setType("FLOAT64"));
        fields.add(new TableFieldSchema().setName("ch4").setType("FLOAT64"));
        fields.add(new TableFieldSchema().setName("ch5").setType("FLOAT64"));
        return new TableSchema().setFields(fields);
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getDateTime() {
        return dateTime;
    }

    public void setDateTime(String dateTime) {
        this.dateTime = dateTime;
    }

    public Integer getCh0() {
        return ch0;
    }

    public void setCh0(Integer ch0) {
        this.ch0 = ch0;
    }

    public String getCh1() {
        return ch1;
    }

    public void setCh1(String ch1) {
        this.ch1 = ch1;
    }

    public double getCh2() {
        return ch2;
    }

    public void setCh2(double ch2) {
        this.ch2 = ch2;
    }

    public double getCh3() {
        return ch3;
    }

    public void setCh3(double ch3) {
        this.ch3 = ch3;
    }

    public double getCh4() {
        return ch4;
    }

    public void setCh4(double ch4) {
        this.ch4 = ch4;
    }

    public double getCh5() {
        return ch5;
    }

    public void setCh5(double ch5) {
        this.ch5 = ch5;
    }

    // Convert TableRow for writing to big query
    public TableRow makeTableRow() {
        return new TableRow()
                .set("module", this.module)
                .set("datetime", this.dateTime)
                .set("ch0", this.ch0)
                .set("ch1", this.ch1)
                .set("ch2", this.ch2)
                .set("ch3", this.ch3)
                .set("ch4", this.ch4)
                .set("ch5", this.ch5);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageMappedBq that = (MessageMappedBq) o;
        return Double.compare(that.getCh2(), getCh2()) == 0 &&
                Double.compare(that.getCh3(), getCh3()) == 0 &&
                Double.compare(that.getCh4(), getCh4()) == 0 &&
                Double.compare(that.getCh5(), getCh5()) == 0 &&
                Objects.equals(getModule(), that.getModule()) &&
                Objects.equals(getDateTime(), that.getDateTime()) &&
                Objects.equals(getCh0(), that.getCh0()) &&
                Objects.equals(getCh1(), that.getCh1());
    }

    @Override
    public int hashCode() {
        return Objects.hash(module, dateTime, ch0, ch1, ch2, ch3, ch4, ch5);
    }
}
