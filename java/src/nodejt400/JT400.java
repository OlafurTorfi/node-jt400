package nodejt400;

import java.beans.PropertyVetoException;
import java.math.BigDecimal;
import java.sql.Connection;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400JDBCConnectionHandle;
import com.ibm.as400.access.AS400JDBCConnectionPool;
import com.ibm.as400.access.AS400JDBCConnectionPoolDataSource;
import com.ibm.as400.access.AS400Message;
import com.ibm.as400.access.AS400PackedDecimal;
import com.ibm.as400.access.AS400Structure;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.ProgramCall;
import com.ibm.as400.access.ProgramParameter;
import com.ibm.as400.access.QSYSObjectPathName;

public class JT400 implements ConnectionProvider
{
	private final AS400JDBCConnectionPool sqlPool;

	private final JdbcJsonClient client;

	public JT400(JSONObject jsonConf)
	{
		Props conf = new Props(jsonConf);
		AS400JDBCConnectionPoolDataSource ds = new AS400JDBCConnectionPoolDataSource();
		ds.setServerName(conf.get("host"));
		ds.setUser(conf.get("user"));
		ds.setPassword(conf.get("password"));
		String naming = conf.get("naming", "system");
		ds.setNaming(naming);
		ds.setDateFormat(conf.get("dateFormat", "iso"));
		ds.setMetaDataSource(0);
		String value = conf.get("sort");
		if (value != null)
		{
			ds.setSort(value);
		}
		value = conf.get("sortTable");
		if (value != null)
		{
			ds.setSortTable(value);
		}
		value = conf.get("sortLanguage");
		if (value != null)
		{
			ds.setSortLanguage(value);
		}
		value = conf.get("libraries");
		if (value != null)
		{
			ds.setLibraries(value);
		}
		this.sqlPool = new AS400JDBCConnectionPool(ds);
		this.client = new JdbcJsonClient(this);
		Runtime.getRuntime().addShutdownHook(new Thread()
		{
			@Override
			public void run()
			{
				System.out.println("close connectionpool.");
				sqlPool.close();
			}
		});
	}

	@Override
	public Connection getConnection() throws Exception
	{
		return sqlPool.getConnection();
	}

	@Override
	public void close(Connection c) throws Exception
	{
		c.close();
	}

	public static final JT400 getInstance(String jsonConf)
	{
		JSONObject conf = (JSONObject) JSONValue.parse(jsonConf);
		return new JT400(conf);
	}

	public String query(String sql, String paramsJson)
			throws Exception
	{
		return client.query(sql, paramsJson);
	}

	public ResultSetStream executeAsStream(String sql, String paramsJson, int bufferSize, boolean metadata)
			throws Exception
	{
		return client.executeAsStream(sql, paramsJson, bufferSize, metadata);
	}

	public TablesReadStream getTablesAsStream(String catalog, String schema, String table) throws Exception
	{
		return client.getTablesAsStream(catalog, schema, table);
	}

	public String getColumns(String catalog, String schema, String tableNamePattern, String columnNamePattern)
	throws Exception
	{
		return client.getColumns(catalog, schema, tableNamePattern, columnNamePattern);
	}

	public int update(String sql, String paramsJson)
			throws Exception
	{
		return client.update(sql, paramsJson);
	}

	public double insertAndGetId(String sql, String paramsJson)
			throws Exception
	{
		return client.insertAndGetId(sql, paramsJson);
	}

	public Transaction createTransaction() throws Exception
	{
		return new Transaction(getConnection());
	}

	public void close()
	{
		sqlPool.close();
	}

	public Pgm pgm(String programName, String paramsSchemaJsonStr)
	{
		return new Pgm(programName, paramsSchemaJsonStr);
	}

	public class Pgm
	{
		private final String name;

		private final String paramsSchemaJsonStr;

		public Pgm(String programName, String paramsSchemaJsonStr)
		{
			this.name = programName;
			this.paramsSchemaJsonStr = paramsSchemaJsonStr;
		}

		public String run(String paramsJsonStr) throws Exception
		{
			Connection c = sqlPool.getConnection();
			JSONObject result = new JSONObject();
			try
			{
				JSONArray paramsSchema = (JSONArray) JSONValue.parse(paramsSchemaJsonStr);
				int n = paramsSchema.size();
				PgmParam[] paramArray = new PgmParam[n];
				ProgramParameter[] pgmParamArray = new ProgramParameter[n];
				for (int i = 0; i < n; i++)
				{
					JSONObject paramJson = (JSONObject) paramsSchema.get(i);
					paramArray[i] = PgmParam.parse(paramJson);
					pgmParamArray[i] = paramArray[i].asPgmParam();
				}

				JSONObject params = (JSONObject) JSONValue.parse(paramsJsonStr);
				for (PgmParam param : paramArray)
				{
					param.setValue(params.get(param.getName()));
				}

				AS400JDBCConnectionHandle handle = (AS400JDBCConnectionHandle) c;
				AS400 as400 = handle.getSystem();
				ProgramCall call = new ProgramCall(as400);

				//Run
				call.setProgram(QSYSObjectPathName.toPath("*LIBL", name, "PGM"), pgmParamArray);
				if (!call.run())
				{
					AS400Message[] ms = call.getMessageList();
					StringBuffer error = new StringBuffer();
					error.append("Program ");
					error.append(call.getProgram());
					error.append(" did not run: ");
					for (int i = 0; i < ms.length; i++)
					{
						error.append(ms[i].toString());
						error.append("\n");
					}
					throw new Exception(error.toString());
				}

				//Get results
				for (PgmParam param : paramArray)
				{
					result.put(param.getName(), param.getValue());
				}

			}
			catch (Exception ex)
			{
				throw ex;
			}
			finally
			{
				c.close();
			}

			return result.toJSONString();

		}
	}
}

abstract class PgmParam
{
	private final String name;

	private final int size;

	protected ProgramParameter pgmParam;

	public PgmParam(String name, int size)
	{
		this.name = name;
		this.size = size;
	}

	public PgmParam(String name, Props paramDef)
	{
		this(name, paramDef.getInt("size"));
	}

	public String getName()
	{
		return name;
	}

	public ProgramParameter asPgmParam()
	{
		pgmParam = size == -1 ? new ProgramParameter() : new ProgramParameter(size);
		return pgmParam;
	}

	public void setValue(Object value) throws PropertyVetoException
	{
		pgmParam.setInputData(getAS400DataType().toBytes(toInputValue(value)));
	}

	public Object getValue()
	{
		return toOutputValue(getAS400DataType().toObject(pgmParam.getOutputData()));
	}

	public abstract Object toInputValue(Object value);

	public Object toOutputValue(Object output)
	{
		return output;
	}

	public static final PgmParam parse(JSONObject paramJson)
	{
		Object nameAttr = paramJson.get("name");
		String name = null;
		Props paramDef = null;
		if (nameAttr == null ||
				nameAttr instanceof JSONObject ||
				nameAttr instanceof JSONArray)
		{
			name = (String) paramJson.keySet().iterator().next();
			Object paramsObject = paramJson.get(name);
			if (paramsObject instanceof JSONArray)
			{
				return new StructPgmParam(name, (JSONArray) paramsObject);
			}
			else
			{
				paramDef = new Props((JSONObject) paramsObject);
			}
		}
		else
		{
			paramDef = new Props(paramJson);
			name = (String) nameAttr;
		}
		if ("decimal".equals(paramDef.get("type")) || paramDef.has("decimals"))
		{
			return new DecimalPgmParam(name, paramDef);
		}
		return new TextPgmParam(name, paramDef);
	}

	public abstract AS400DataType getAS400DataType();
}

class TextPgmParam extends PgmParam
{
	private final AS400Text parser;

	public TextPgmParam(String name, Props paramDef)
	{
		super(name, paramDef);
		parser = new AS400Text(paramDef.getInt("size"), "Cp871");
	}

	@Override
	public Object toInputValue(Object value)
	{
		return value == null ? "" : value;
	}

	@Override
	public Object toOutputValue(Object output)
	{
		return ((String) output).trim();
	}

	@Override
	public AS400DataType getAS400DataType()
	{
		return parser;
	}

}

class DecimalPgmParam extends PgmParam
{
	private final AS400PackedDecimal parser;

	public DecimalPgmParam(String name, Props paramDef)
	{
		super(name, paramDef);
		parser = new AS400PackedDecimal(paramDef.getInt("size"), paramDef.getInt("decimals"));
	}

	@Override
	public Object toInputValue(Object value)
	{
		return new BigDecimal(value == null ? "0" : value.toString());
	}

	@Override
	public AS400DataType getAS400DataType()
	{
		return parser;
	}
}

class StructPgmParam extends PgmParam
{
	private final PgmParam[] params;

	private final AS400Structure asStructure;

	public StructPgmParam(String name, JSONArray structure)
	{
		super(name, -1);
		int n = structure.size();
		AS400DataType[] asDT = new AS400DataType[n];
		params = new PgmParam[n];
		for (int i = 0; i < n; i++)
		{
			JSONObject paramJson = (JSONObject) structure.get(i);
			params[i] = PgmParam.parse(paramJson);
			asDT[i] = params[i].getAS400DataType();
		}

		asStructure = new AS400Structure(asDT);
	}

	@Override
	public void setValue(Object value) throws PropertyVetoException
	{
		pgmParam.setOutputDataLength(asStructure.getByteLength());
		super.setValue(value);
	}

	@Override
	public Object toInputValue(Object value)
	{
		JSONObject jsonParams = (JSONObject) value;
		Object[] values = new Object[params.length];
		for (int i = 0; i < params.length; i++)
		{
			Object subValue = jsonParams.get(params[i].getName());
			values[i] = params[i].toInputValue(subValue);
		}
		return values;
	}

	@Override
	public Object toOutputValue(Object output)
	{
		JSONObject result = new JSONObject();
		Object[] values = (Object[]) output;
		for (int i = 0; i < params.length; i++)
		{
			PgmParam p = params[i];
			result.put(p.getName(), p.toOutputValue(values[i]));
		}
		return result;
	}

	@Override
	public AS400DataType getAS400DataType()
	{
		return asStructure;
	}

}

class Props
{
	private final JSONObject w;

	public Props(JSONObject w)
	{
		this.w = w;
	}

	public boolean has(String key)
	{
		return w.get(key) != null;
	}

	public String get(String key)
	{
		return (String) w.get(key);
	}

	public int getInt(String key)
	{
		return ((Number) w.get(key)).intValue();
	}

	public String get(String key, String defaultValue)
	{
		String value = (String) w.get(key);
		return value == null ? defaultValue : value;
	}
}