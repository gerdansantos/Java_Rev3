package bloom;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.MultipleInputs;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hadoop.util.bloom.BloomFilter;
import org.apache.hadoop.util.bloom.Key;
import org.apache.hadoop.util.hash.Hash;
import org.htuple.ShuffleUtils;
import org.htuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StockDividendFilter extends Configured implements Tool {
  private static final String FILTER_FILE = "filters/dividendfilter";

  private enum BloomCounters {
    FALSE_POSITIVES;
  }

  private enum TupleFields {
    TYPE, SYMBOL, DATE;
  }

  private enum JoinData {
    DIVIDENDS, STOCKS;
  }

  public static class BloomMapper extends
      Mapper<LongWritable, Text, NullWritable, BloomFilter> {
    private String stockSymbol;
    private NullWritable outputKey = NullWritable.get();
    private BloomFilter outputValue;

    @Override
    protected void setup(Context context) throws IOException,
        InterruptedException {
      stockSymbol = context.getConfiguration().get("stockSymbol");
      outputValue = new BloomFilter(1000, 20, Hash.MURMUR_HASH);
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
        throws IOException, InterruptedException {
      String[] words = StringUtils.split(value.toString(), '\\', ',');
      if (words[1].equals(stockSymbol)) {
        Tuple stock = new Tuple();
        stock.setString(TupleFields.SYMBOL, words[1]);
        stock.setString(TupleFields.DATE, words[2]);
        Key stockKey = new Key(stock.toString().getBytes());
        outputValue.add(stockKey);
      }
    }

    @Override
    protected void cleanup(Context context) throws IOException,
        InterruptedException {
      context.write(outputKey, outputValue);
    }
  }

  public static class BloomReducer extends
      Reducer<NullWritable, BloomFilter, NullWritable, NullWritable> {
    private BloomFilter allValues;

    @Override
    protected void setup(Context context) throws IOException,
        InterruptedException {
      allValues = new BloomFilter(1000, 20, Hash.MURMUR_HASH);
    }

    @Override
    protected void reduce(NullWritable key, Iterable<BloomFilter> values,
                          Context context) throws IOException, InterruptedException {
      for (BloomFilter filter : values) {
        allValues.or(filter);
      }
    }

    @Override
    protected void cleanup(Context context) throws IOException,
        InterruptedException {
      Configuration conf = context.getConfiguration();
      Path path = new Path(FILTER_FILE);
      FSDataOutputStream out = path.getFileSystem(conf).create(path);
      allValues.write(out);
      out.close();
    }
  }

  public static class StockFilterMapper extends
      Mapper<LongWritable, Text, Tuple, DoubleWritable> {
    private BloomFilter dividends;
    private String stockSymbol;
    private DoubleWritable outputValue = new DoubleWritable();
    Tuple outputKey = new Tuple();

    @Override
    protected void setup(Context context) throws IOException,
        InterruptedException {
      Path filter_file = new Path(FILTER_FILE);
      stockSymbol = context.getConfiguration().get("stockSymbol");

      dividends = new BloomFilter(1000, 20, Hash.MURMUR_HASH);
      FileSystem fs = FileSystem.get(context.getConfiguration());
      FSDataInputStream in = fs.open(filter_file);
      dividends.readFields(in);
      in.close();
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
        throws IOException, InterruptedException {
      String[] words = StringUtils.split(value.toString(), '\\', ',');
      if (words[1].equals(stockSymbol)) {
        outputKey.setString(TupleFields.SYMBOL, words[1]);
        outputKey.setString(TupleFields.DATE, words[2]);
        // Instantiate a Key and check for membership in the Bloom filter
        Key stockKey = new Key(outputKey.toString().getBytes());
        if (dividends.membershipTest(stockKey)) {
          outputKey.setInt(TupleFields.TYPE, JoinData.STOCKS.ordinal());
          outputValue.set(Double.parseDouble(words[6]));
          context.write(outputKey, outputValue);
        }
      }
    }
  }

  public static class DividendMapper extends
      Mapper<LongWritable, Text, Tuple, DoubleWritable> {
    private String stockSymbol;
    private DoubleWritable outputValue = new DoubleWritable();
    Tuple outputKey = new Tuple();

    @Override
    protected void setup(Context context) throws IOException,
        InterruptedException {
      stockSymbol = context.getConfiguration().get("stockSymbol");
    }

    @Override
    protected void map(LongWritable key, Text value, Context context)
        throws IOException, InterruptedException {
      String[] words = StringUtils.split(value.toString(), '\\', ',');
      if (words[1].equals(stockSymbol)) {
        outputKey.setString(TupleFields.SYMBOL, words[1]);
        outputKey.setString(TupleFields.DATE, words[2]);
        outputKey.setInt(TupleFields.TYPE, JoinData.DIVIDENDS.ordinal());
        outputValue.set(Double.parseDouble(words[3]));
        context.write(outputKey, outputValue);
      }
    }
  }

  public static class StockFilterReducer extends
      Reducer<Tuple, DoubleWritable, Text, DoubleWritable> {
    private static final Logger LOG = LoggerFactory
        .getLogger(StockFilterReducer.class);
    private Text outputKey = new Text();

    @Override
    protected void reduce(Tuple key, Iterable<DoubleWritable> values,
                          Context context) throws IOException, InterruptedException {
      DoubleWritable dividend = null;

      for (DoubleWritable value : values) {
        // The dividend record (if any) should appear first. Only output the
        // stock data if there's a matching dividend record. False positives
        // from the bloom filter could have caused some extra stock records to
        // be sent to the reducer
        if (key.getInt(TupleFields.TYPE) == JoinData.DIVIDENDS.ordinal()) {
          // Copy the dividend so that the framework doesn't overwrite it the
          // next time through the loop
          dividend = new DoubleWritable(value.get());
        }
        else if (dividend != null) {
          outputKey.set(key.toString());
          context.write(outputKey, value);
        }
      }

      if (dividend == null) {
        LOG.warn("False positive detected for stock: {}", key.toString());
        context.getCounter(BloomCounters.FALSE_POSITIVES).increment(1);
      }
    }

  }

  @Override
  public int run(String[] args) throws Exception {
    Job job1 = Job.getInstance(getConf(), "CreateBloomFilter");
    job1.setJarByClass(getClass());
    Configuration conf = job1.getConfiguration();
    conf.set("stockSymbol", args[0]);

    FileInputFormat.setInputPaths(job1, new Path("dividends"));

    job1.setMapperClass(BloomMapper.class);
    job1.setReducerClass(BloomReducer.class);
    job1.setInputFormatClass(TextInputFormat.class);
    job1.setOutputFormatClass(NullOutputFormat.class);
    job1.setMapOutputKeyClass(NullWritable.class);
    job1.setMapOutputValueClass(BloomFilter.class);
    job1.setOutputKeyClass(NullWritable.class);
    job1.setOutputValueClass(NullWritable.class);
    job1.setNumReduceTasks(1);

    boolean job1success = job1.waitForCompletion(true);
    if (!job1success) {
      System.out.println("The CreateBloomFilter job failed!");
      return -1;
    }

    Job job2 = Job.getInstance(conf, "FilterStocksJob");
    job2.setJarByClass(getClass());
    conf = job2.getConfiguration();

    Path out = new Path("bloomoutput");
    out.getFileSystem(conf).delete(out, true);
    FileInputFormat.setInputPaths(job2, new Path("stocks"));
    FileOutputFormat.setOutputPath(job2, out);

    Path stocks = new Path("stocks");
    Path dividends = new Path("dividends");
    MultipleInputs.addInputPath(job2, stocks, TextInputFormat.class,
        StockFilterMapper.class);
    MultipleInputs.addInputPath(job2, dividends, TextInputFormat.class,
        DividendMapper.class);
    job2.setReducerClass(StockFilterReducer.class);

    job2.setOutputFormatClass(TextOutputFormat.class);
    job2.setMapOutputKeyClass(Tuple.class);
    job2.setMapOutputValueClass(DoubleWritable.class);
    job2.setOutputKeyClass(Text.class);
    job2.setOutputValueClass(DoubleWritable.class);

    ShuffleUtils.configBuilder()
        .useNewApi()
        .setPartitionerIndices(TupleFields.SYMBOL, TupleFields.DATE)
        .setSortIndices(TupleFields.values())
        .setGroupIndices(TupleFields.SYMBOL, TupleFields.DATE)
        .configure(job2.getConfiguration());

    boolean job2success = job2.waitForCompletion(true);
    if (!job2success) {
      System.out.println("The FilterStocksJob failed!");
      return -1;
    }
    return 1;
  }

  public static void main(String[] args) {
    int result = 0;
    try {
      result = ToolRunner.run(new Configuration(), new StockDividendFilter(),
          args);
    }
    catch (Exception e) {
      e.printStackTrace();
    }
    System.exit(result);

  }

}
