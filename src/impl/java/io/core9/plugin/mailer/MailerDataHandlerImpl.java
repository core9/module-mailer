package io.core9.plugin.mailer;

import io.core9.plugin.admin.plugins.AdminConfigRepository;
import io.core9.plugin.database.mongodb.MongoDatabase;
import io.core9.plugin.filesmanager.FileRepository;
import io.core9.plugin.server.request.Request;
import io.core9.plugin.server.vertx.VertxServer;
import io.core9.plugin.widgets.datahandler.DataHandler;
import io.core9.plugin.widgets.datahandler.DataHandlerFactoryConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import jdk.nashorn.api.scripting.NashornScriptEngineFactory;
import jdk.nashorn.api.scripting.ScriptObjectMirror;

import com.ee.dynamicmongoquery.MongoQuery;
import com.ee.dynamicmongoquery.MongoQueryParser;
import com.google.common.io.ByteStreams;
import com.mongodb.BasicDBList;
import com.mongodb.DB;

import net.minidev.json.JSONObject;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import net.xeoh.plugins.base.annotations.injections.InjectPlugin;

@PluginImplementation
public class MailerDataHandlerImpl implements
		MailerDataHandler<MailerDataHandlerConfig> {

	@InjectPlugin
	private AdminConfigRepository configRepository;

	@InjectPlugin
	private VertxServer server;

	@InjectPlugin
	private MongoDatabase database;

	@InjectPlugin
	private FileRepository repository;

	@Override
	public String getName() {
		return "Mailer";
	}

	private NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
	// secure
	private ScriptEngine sengine = factory
			.getScriptEngine(new String[] { "--no-java" });

	@Override
	public Class<? extends DataHandlerFactoryConfig> getConfigClass() {
		return MailerDataHandlerConfig.class;
	}

	@SuppressWarnings("unused")
	@Override
	public DataHandler<MailerDataHandlerConfig> createDataHandler(
			final DataHandlerFactoryConfig options) {
		return new DataHandler<MailerDataHandlerConfig>() {

			private String js;
			private Object response;
			private Map<String, Object> result = new HashMap<String, Object>();
			private MongoQueryParser parser = new MongoQueryParser();
			private Object preDatabase;
			private Object postDatabase;

			@Override
			public Map<String, Object> handle(Request req) {

				Map<String, Object> mailer = getScriptPath(options, req);
				if (mailer == null) {
					mailer = new HashMap<String, Object>();
					Map<String, Object> file = getJsFile(options, req);

					getEngine(file);

					Invocable invocable = (Invocable) sengine;
					try {
						Object preDatabase = invocable.invokeFunction(
								"preDatabaseFilter", server);
					} catch (NoSuchMethodException e) {
						e.printStackTrace();
					} catch (ScriptException e) {
						e.printStackTrace();
					}

					JSONObject server = getServerObject();

					try {
						preDatabase = invocable.invokeFunction(
								"preDatabaseFilter", server);
					} catch (NoSuchMethodException e) {
						e.printStackTrace();
					} catch (ScriptException e) {
						e.printStackTrace();
					}

					JSONObject dbResults = getDatabaseResults(req, sengine,
							invocable, preDatabase);

					try {
						postDatabase = invocable.invokeFunction(
								"postDatabaseFilter", dbResults);
					} catch (NoSuchMethodException e) {
						e.printStackTrace();
					} catch (ScriptException e) {
						e.printStackTrace();
					}

					mailer.put("mailer", postDatabase);

				}
				result.put("mailer", mailer);
				return result;
			}

			private JSONObject getDatabaseResults(Request req,
					ScriptEngine sengine, Invocable invocable,
					Object preDatabase) {

				DB rawDb = database.getDb((String) req.getVirtualHost()
						.getContext("database"));

				JSONObject jsonObject = new JSONObject();
				ScriptObjectMirror queries = null;
				try {
					queries = (ScriptObjectMirror) invocable.invokeFunction(
							"databaseQueries", preDatabase);
				} catch (NoSuchMethodException e) {
					e.printStackTrace();
				} catch (ScriptException e) {
					e.printStackTrace();
				}

				for (Entry<String, Object> object : queries.entrySet()) { 
					String key = object.getKey();
					String value = (String) object.getValue();
					System.out.println(key + " : " + value);

					MongoQuery mongoQuery = parser.parse(value,
							new HashMap<String, String>());
					BasicDBList results = mongoQuery.execute(rawDb);

					jsonObject.put(key, results);
				}
				return jsonObject;
			}

			private JSONObject getServerObject() {
				JSONObject server = new JSONObject();
				JSONObject request = new JSONObject();
				request.put("path", "/mailer");
				server.put("request", request);
				return server;
			}

			private void getEngine(Map<String, Object> file) {
				try {
					js = new String(ByteStreams.toByteArray((InputStream) file
							.get("stream")));
				} catch (IOException e) {
					e.printStackTrace();
				}

				try {
					sengine.eval(js);
				} catch (ScriptException e) {
					e.printStackTrace();
				}
			}

			private Map<String, Object> getJsFile(
					final DataHandlerFactoryConfig options, Request req) {
				String id = ((MailerDataHandlerConfig) options)
						.getMailerId(req);

				Map<String, Object> file = repository.getFileContentsByName(
						req.getVirtualHost(), id);
				return file;
			}

			private Map<String, Object> getScriptPath(
					final DataHandlerFactoryConfig options, Request req) {
				Map<String, Object> mailer = configRepository.readConfig(
						req.getVirtualHost(),
						((MailerDataHandlerConfig) options).getMailerId(req));
				return mailer;
			}

			@Override
			public MailerDataHandlerConfig getOptions() {
				return (MailerDataHandlerConfig) options;
			}
		};
	}
}
