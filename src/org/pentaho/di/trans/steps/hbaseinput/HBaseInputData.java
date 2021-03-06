/*******************************************************************************
 *
 * Pentaho Big Data
 *
 * Copyright (C) 2002-2012 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/

package org.pentaho.di.trans.steps.hbaseinput;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.core.row.ValueMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.trans.step.BaseStepData;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.hbase.mapping.HBaseValueMeta;
import org.pentaho.hbase.mapping.Mapping;

/**
 * Class providing an input step for reading data from an HBase table
 * according to meta data mapping info stored in a separate HBase table
 * called "pentaho_mappings". See org.pentaho.hbase.mapping.Mapping for
 * details on the meta data format.
 * 
 * @author Mark Hall (mhall{[at]}pentaho{[dot]}com)
 * @version $Revision$
 *
 */
public class HBaseInputData extends BaseStepData implements StepDataInterface {
  
  /** The output data format */
  protected RowMetaInterface m_outputRowMeta;
    
  /**
   * Get the output row format
   * 
   * @return the output row format
   */
  public RowMetaInterface getOutputRowMeta() {
    return m_outputRowMeta;
  }
  
  /**
   * Set the output row format
   * 
   * @param rmi the output row format
   */
  public void setOutputRowMeta(RowMetaInterface rmi) {
    m_outputRowMeta = rmi;
  }
  
  /**
   * Get a configured connection to HBase. A connection can be obtained via
   * a list of host(s) that zookeeper is running on or via the hbase-site.xml
   * (and optionally hbase-default.xml) file.
   * 
   * @param zookeeperHosts a comma separated list of hosts that zookeeper is
   * running on
   * @param zookeeperPort an (optional) port that zookeeper is listening on. If not
   * specified, then the default for zookeeper is used
   * @param coreConfig URL to the hbase-site.xml (may be null)
   * @param defaultConfig URL to the hbase-default.xml (may be null)
   * @return a Configuration object that can be used ot access HBase.
   * @throws IOException if a problem occurs.
   */
  public static Configuration getHBaseConnection(String zookeeperHosts, 
      String zookeeperPort, URL coreConfig, URL defaultConfig) 
    throws IOException {
    Configuration con = new Configuration();
    
    if (defaultConfig != null) {
      con.addResource(defaultConfig);
    } else {
      // hopefully it's in the classpath
      con.addResource("hbase-default.xml");
    }
    
    if (coreConfig != null) {
      con.addResource(coreConfig);
    } else {
      // hopefully it's in the classpath
      con.addResource("hbase-site.xml");
    } 
    
    if (!Const.isEmpty(zookeeperHosts)) {
      // override default and site with this
      con.set("hbase.zookeeper.quorum", zookeeperHosts);
    }
    
    if (!Const.isEmpty(zookeeperPort)) {
      try {
        int port = Integer.parseInt(zookeeperPort);
        con.setInt("hbase.zookeeper.property.clientPort", port);
      } catch (NumberFormatException e) { 
        System.err.println("Unable to parse zookeeper port!");
      }
    }
    
    return con;    
  }
  
  /**
   * Utility method to covert a string to a URL object.
   * 
   * @param pathOrURL file or http URL as a string
   * @return a URL
   * @throws MalformedURLException if there is a problem with the URL.
   */
  public static URL stringToURL(String pathOrURL) throws MalformedURLException {
    URL result = null;
    
    if (!Const.isEmpty(pathOrURL)) {
      if (pathOrURL.toLowerCase().startsWith("http://") ||
          pathOrURL.toLowerCase().startsWith("file://")) {
        result = new URL(pathOrURL);
      } else {
        String c = "file://" + pathOrURL;
        result = new URL(c);
      }
    }
    
    return result;
  }
  
  protected List<Object[]> m_decodedTuples;
  protected int m_keyIndex = -1;
  protected int m_familyIndex = -1;
  protected int m_colNameIndex = -1;
  protected int m_valueIndex = -1;
  protected int m_timestampIndex = -1;
  protected List<byte[]> m_userSpecifiedFamilies;
  protected List<String> m_userSpecifiedFamiliesHumanReadable;
  
  protected List<HBaseValueMeta> m_tupleColsFromAliasMap;
  
  public List<Object[]> hbaseRowToKettleTupleMode(Result hRow, Mapping mapping,
      Map<String, HBaseValueMeta> tupleColsMappedByAlias) throws KettleException {
    
    if (m_decodedTuples == null) {
      m_tupleColsFromAliasMap = new ArrayList<HBaseValueMeta>();
      // add the key first - type (or name for that matter) 
      // is not important as this is just a dummy placeholder
      // here so that indexes into m_tupleColsFromAliasMap align with the output row meta
      // format
      HBaseValueMeta keyMeta = new HBaseValueMeta(mapping.getKeyName() + 
          HBaseValueMeta.SEPARATOR + "dummy", 
          ValueMetaInterface.TYPE_INTEGER, 0, 0);
      m_tupleColsFromAliasMap.add(keyMeta);
      
      for (String alias : tupleColsMappedByAlias.keySet()) {
        m_tupleColsFromAliasMap.add(tupleColsMappedByAlias.get(alias));
      }
    }
    
    return hbaseRowToKettleTupleMode(hRow, mapping, m_tupleColsFromAliasMap);    
  }
  
  public List<Object[]> hbaseRowToKettleTupleMode(Result hRow, Mapping mapping, 
      List<HBaseValueMeta> tupleCols) throws KettleException {
    
    if (m_decodedTuples == null) {
      m_decodedTuples = new ArrayList<Object[]>();
      m_keyIndex = getOutputRowMeta().indexOfValue(mapping.getKeyName());
      m_familyIndex = getOutputRowMeta().indexOfValue("Family");
      m_colNameIndex = getOutputRowMeta().indexOfValue("Column");
      m_valueIndex = getOutputRowMeta().indexOfValue("Value");
      m_timestampIndex = getOutputRowMeta().indexOfValue("Timestamp");
      
      if (!Const.isEmpty(mapping.getTupleFamilies())) {
        String[] familiesS = mapping.getTupleFamilies().split(HBaseValueMeta.SEPARATOR);
        m_userSpecifiedFamilies = new ArrayList<byte[]>();
        m_userSpecifiedFamiliesHumanReadable = new ArrayList<String>();
        
        for (String family : familiesS) {
          m_userSpecifiedFamiliesHumanReadable.add(family);
          m_userSpecifiedFamilies.add(Bytes.toBytes(family.trim()));
        }
      }
    } else {
      m_decodedTuples.clear();
    }
    
    byte[] rawKey = hRow.getRow();
    Object decodedKey = HBaseValueMeta.decodeKeyValue(rawKey, mapping);
    
    NavigableMap<byte[],NavigableMap<byte[],NavigableMap<Long,byte[]>>> rowData = 
      hRow.getMap();
    
    if (!Const.isEmpty(mapping.getTupleFamilies())) {      
      int i = 0;
      for (byte[] family : m_userSpecifiedFamilies) {
        NavigableMap<byte[], NavigableMap<Long, byte[]>> colMap = rowData.get(family);
        for (byte[] colName : colMap.keySet()) {
          NavigableMap<Long, byte[]> valuesByTimestamp = colMap.get(colName);
          
          Object[] newTuple = RowDataUtil.allocateRowData(getOutputRowMeta().size());
          
          // row key
          if (m_keyIndex != -1) {
            newTuple[m_keyIndex] = decodedKey;
          }
          
          // get value of most recent column value
          Map.Entry<Long, byte[]> mostRecentColVal = valuesByTimestamp.lastEntry();
          
          // store the timestamp
          if (m_timestampIndex != -1) {
            newTuple[m_timestampIndex] = mostRecentColVal.getKey();
          }
          
          // column name
          if (m_colNameIndex != -1) {
            HBaseValueMeta colNameMeta = tupleCols.get(m_colNameIndex);
            Object decodedColName = HBaseValueMeta.decodeColumnValue(colName, colNameMeta);
            newTuple[m_colNameIndex] = decodedColName;
          }
          
          // column value
          if (m_valueIndex != -1) {            
            HBaseValueMeta colValueMeta = tupleCols.get(m_valueIndex);
            Object decodedValue = HBaseValueMeta.
              decodeColumnValue(mostRecentColVal.getValue(), colValueMeta);
            newTuple[m_valueIndex] = decodedValue;
          }          
          
          // column family
          if (m_familyIndex != -1) {
            newTuple[m_familyIndex] = m_userSpecifiedFamiliesHumanReadable.get(i);
          }
          
          m_decodedTuples.add(newTuple);
        }
        i++;
      }
    } else {
      // process all column families
      for (byte[] family : rowData.keySet()) {
        
        // column family
        Object decodedFamily = null;
        if (m_familyIndex != -1) {
          HBaseValueMeta colFamMeta = tupleCols.get(m_familyIndex);
          decodedFamily = HBaseValueMeta.decodeColumnValue(family, colFamMeta);
        }
        
        NavigableMap<byte[], NavigableMap<Long, byte[]>> colMap = rowData.get(family);
        for (byte[] colName : colMap.keySet()) {
          NavigableMap<Long, byte[]> valuesByTimestamp = colMap.get(colName);
          
          Object[] newTuple = RowDataUtil.allocateRowData(getOutputRowMeta().size());
          
          // row key
          if (m_keyIndex != -1) {
            newTuple[m_keyIndex] = decodedKey;
          }
          
          // get value of most recent column value
          Map.Entry<Long, byte[]> mostRecentColVal = valuesByTimestamp.lastEntry();
          
          // store the timestamp
          if (m_timestampIndex != -1) {
            newTuple[m_timestampIndex] = mostRecentColVal.getKey();
          }
          
          // column name
          if (m_colNameIndex != -1) {
            HBaseValueMeta colNameMeta = tupleCols.get(m_colNameIndex);
            Object decodedColName = HBaseValueMeta.decodeColumnValue(colName, colNameMeta);
            newTuple[m_colNameIndex] = decodedColName;
          }
          
          // column value
          if (m_valueIndex != -1) {            
            HBaseValueMeta colValueMeta = tupleCols.get(m_valueIndex);
            Object decodedValue = HBaseValueMeta.
              decodeColumnValue(mostRecentColVal.getValue(), colValueMeta);
            newTuple[m_valueIndex] = decodedValue;
          }          
          
          // column family
          if (m_familyIndex != -1) {
            newTuple[m_familyIndex] = decodedFamily;
          }
          
          m_decodedTuples.add(newTuple);
        }
      }
    }
    
    
    return m_decodedTuples;
  }
}