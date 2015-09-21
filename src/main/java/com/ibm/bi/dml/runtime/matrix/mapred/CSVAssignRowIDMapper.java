/**
 * (C) Copyright IBM Corp. 2010, 2015
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package com.ibm.bi.dml.runtime.matrix.mapred;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.ByteWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

import com.ibm.bi.dml.runtime.DMLRuntimeException;
import com.ibm.bi.dml.runtime.DMLUnsupportedOperationException;
import com.ibm.bi.dml.runtime.instructions.mr.CSVReblockInstruction;
import com.ibm.bi.dml.runtime.matrix.CSVReblockMR;
import com.ibm.bi.dml.runtime.matrix.CSVReblockMR.OffsetCount;
import com.ibm.bi.dml.runtime.transform.DataTransform;
import com.ibm.bi.dml.runtime.transform.OmitAgent;
import com.ibm.bi.dml.runtime.transform.TfUtils;
import com.ibm.bi.dml.runtime.transform.TransformationAgent;
import com.ibm.bi.dml.utils.JSONHelper;

public class CSVAssignRowIDMapper extends MapReduceBase implements Mapper<LongWritable, Text, ByteWritable, OffsetCount>
{	
	
	private ByteWritable outKey=new ByteWritable();
	private long fileOffset=0;
	private long num=0;
	private boolean first=true;
	private OutputCollector<ByteWritable, OffsetCount> outCache=null;
	private String delim=" ";
	private boolean ignoreFirstLine=false;
	private boolean realFirstLine=false;
	private String filename="";
	private boolean headerFile=false;
	
	// members relevant to transform
	TfUtils _agents = null;
	
	@Override
	public void map(LongWritable key, Text value,
			OutputCollector<ByteWritable, OffsetCount> out, Reporter report)
			throws IOException 
	{
		if(first) {
			first=false;
			fileOffset=key.get();
			outCache=out;
		}
		
		if(key.get()==0 && headerFile)//getting the number of colums
		{
			if(!ignoreFirstLine)
			{
				report.incrCounter(CSVReblockMR.NUM_COLS_IN_MATRIX, outKey.toString(), value.toString().split(delim, -1).length);
				if(!omit(value.toString()))
					num++;
			}
			else
				realFirstLine=true;
		}
		else
		{
			if(realFirstLine)
			{
				report.incrCounter(CSVReblockMR.NUM_COLS_IN_MATRIX, outKey.toString(), value.toString().split(delim, -1).length);
				realFirstLine=false;
			}
			if(!omit(value.toString()))
				num++;
		}
	}
	
	@Override
	@SuppressWarnings("deprecation")
	public void configure(JobConf job)
	{	
		byte thisIndex;
		try {
			//it doesn't make sense to have repeated file names in the input, since this is for reblock
			thisIndex=MRJobConfiguration.getInputMatrixIndexesInMapper(job).get(0);
			outKey.set(thisIndex);
			FileSystem fs=FileSystem.get(job);
			Path thisPath=new Path(job.get("map.input.file")).makeQualified(fs);
			filename=thisPath.toString();
			String[] strs=job.getStrings(CSVReblockMR.SMALLEST_FILE_NAME_PER_INPUT);
			Path headerPath=new Path(strs[thisIndex]).makeQualified(fs);
			if(headerPath.toString().equals(filename))
				headerFile=true;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		try {
			CSVReblockInstruction[] reblockInstructions = MRJobConfiguration.getCSVReblockInstructions(job);
			for(CSVReblockInstruction ins: reblockInstructions)
			{
				if(ins.input==thisIndex)
				{
					delim=Pattern.quote(ins.delim); 
					ignoreFirstLine=ins.hasHeader;
					break;
				}
			}
		} catch (DMLUnsupportedOperationException e) {
			throw new RuntimeException(e);
		} catch (DMLRuntimeException e) {
			throw new RuntimeException(e);
		}

		// load properties relevant to transform
		try {
			boolean omit = job.getBoolean(MRJobConfiguration.TF_TRANSFORM, false);
			if ( omit ) 
			{
				/*String[] naStrings = DataTransform.parseNAStrings(job);
				
				FileSystem fs=FileSystem.get(job);
				String specFile = job.get(MRJobConfiguration.TF_SPEC_FILE);
				BufferedReader br = new BufferedReader(new InputStreamReader(fs.open(new Path(specFile))));
				JSONObject spec = JSONHelper.parse(br);*/
				
				_agents = new TfUtils(job, true);
			}
		} 
		catch(IOException e) {
			throw new RuntimeException(e);
		} 
		catch(JSONException e) {
			throw new RuntimeException(e);
		}
	}
	
	private boolean omit(String line)
	{
		return _agents.omit( line.split(delim, -1) );
	}
	
	@Override
	public void close() throws IOException
	{
		outCache.collect(outKey, new OffsetCount(filename, fileOffset, num));
	}
}