package elasticbak;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsResponse;
import org.elasticsearch.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.fasterxml.jackson.databind.ObjectMapper;

import elasticbak.Entities.ArgsSettingEntity;
import elasticbak.Entities.BackupEntity;
import elasticbak.Entities.RestoreDataEntity;
import elasticbak.service.ParallelBackupService;
import elasticbak.service.ParallelRestoreDataService;
import elasticbak.utilities.BackupEsIndex;
import elasticbak.utilities.CheckArgs;
import elasticbak.utilities.ElasticsearchConnector;
import elasticbak.utilities.FileUtilities;
import elasticbak.utilities.RestoreEsIndex;

public class ElasticBakMain {

	private static final Logger logger = LoggerFactory.getLogger(ElasticBakMain.class);

	public static void main(String[] args) throws Exception {
		// 获取系统当前时间
		Date dt = new Date();
		DateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
		String nowtime = df.format(dt);

		ObjectMapper objectMapper = new ObjectMapper();
		ArgsSettingEntity argssetting = new ArgsSettingEntity();

		FileUtilities fileutilities = new FileUtilities();
		CheckArgs check;
		Client client;

		JCommander jc = new JCommander();
		jc.setProgramName("java -jar elasticbak.jar <exp/imp>");

		jc.addObject(argssetting);

		/*
		 * 解析输入参数
		 */
		try {
			jc.parse(args);
		} catch (Exception e) {
			jc.usage();
			e.printStackTrace();
			System.exit(0);
		}

		check=new CheckArgs(argssetting);
		if (argssetting.isHelp()) {
			jc.usage();
			System.exit(0);
		}

		if (!check.check()) {
			jc.usage();
			System.exit(0);
		}
		
//		String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(argssetting);
		String json = objectMapper.writeValueAsString(argssetting);
		logger.info("Your command line setting is: " + json);

		// 索引备份
		if (argssetting.isExp()) {
			ExecutorService execservice = Executors.newFixedThreadPool(argssetting.getThreads());
			CompletionService<Long> completionService = new ExecutorCompletionService<Long>(execservice);

			// 如果备份路径不以文件分隔符结尾，自动添加文件分隔符
			if (!argssetting.getBackupdir().endsWith(File.separator)) {
				argssetting.setBackupdir(argssetting.getBackupdir() + File.separator);
			}

			client = new ElasticsearchConnector(argssetting.getCluster(), argssetting.getHost(), argssetting.getPort())
					.getClient();



			for (String bakidx : check.getBackupindeces()) {
				BackupEntity backup = new BackupEntity();
				String backpath = argssetting.getBackupdir() + bakidx + File.separator;
				backup.setClient(client);
				backup.setBackuppath(backpath);
				backup.setIndexname(bakidx);
				backup.setDocsperfile(argssetting.getFilesize());

				// 创建备份目录
				fileutilities.createFolder(backpath);

				completionService.submit(new ParallelBackupService(new BackupEsIndex(backup)));
			}

			for (String bakidx : check.getBackupindeces()) {
				completionService.take().get();
			}
			System.exit(0);
		}

		// 恢复索引
		if (argssetting.isImp()) {
			ExecutorService execservice = Executors.newFixedThreadPool(argssetting.getThreads());
			CompletionService<Long> completionService = new ExecutorCompletionService<Long>(execservice);
			int tasks = 0;
			// 判断文件是否存在
			File file = new File(argssetting.getMetafile());
			if (!file.exists()) {
				logger.error("Metafile not exists");
				System.exit(0);
			}

			client = new ElasticsearchConnector(argssetting.getCluster(), argssetting.getHost(), argssetting.getPort())
					.getClient();

			RestoreEsIndex restoreindex = new RestoreEsIndex();
			// 从备份meta文件重建索引
			restoreindex.CreateIdxFromMetaFile(client, argssetting.getRestoreindex(),
					new File(argssetting.getMetafile()));

			// 恢复数据
			List<File> datafiles = fileutilities.getFilesInTheFolder(argssetting.getBackupset());
			for (File f : datafiles) {
				if (f.getName().endsWith(".data")) {
					RestoreDataEntity data = new RestoreDataEntity();
					data.setClient(client);
					data.setIndexname(argssetting.getRestoreindex());
					data.setDatafile(f);
					RestoreEsIndex ridx = new RestoreEsIndex();
					ridx.setRestordata(data);
					completionService.submit(new ParallelRestoreDataService(ridx));
					tasks++;
				}
			}

			for (int i = 0; i < tasks; i++) {
				completionService.take().get();
			}

			client.close();
			System.exit(0);

		}
		// 解析脚本文件并执行相关操作
		// if (argssetting.getScript_file() != null) {
		// String scriptstring = new
		// JsonUtilities().ReadJsonFile(argssetting.getScript_file());
		// ScriptEntity script = (ScriptEntity)
		// objectMapper.readValue(scriptstring, ScriptEntity.class);
		// String json =
		// objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(script);
		// logger.info("Your Script setting is: \n\t" + json);
		// JsonNode node = objectMapper.readTree(scriptstring);
		//
		// if (node.get("indexes") == null) {
		// logger.info("Script 'indexes' must be set!");
		// return;
		// }
		//
		// List<IndexesRelationEntity> indexrelation =
		// script.getIndexesrelation();
		//
		// for (IndexesRelationEntity idx : indexrelation) {
		// sourceclient = new ElasticsearchConnector(script.getSource_cluster(),
		// script.getSource_host(),
		// script.getSource_port()).getClient();
		// targetclient = new ElasticsearchConnector(script.getTarget_cluster(),
		// script.getTarget_host(),
		// script.getTarget_port()).getClient();
		// String idxjson =
		// objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(idx);
		// logger.info("\n\t" + idxjson);
		// if (idx.getSource_index() == null || idx.getTarget_index() == null) {
		// logger.info("In script 'indexes.source_index' and
		// 'indexes.target_index' must been set!");
		// continue;
		// }
		//
		// switch (idx.getType().toUpperCase()) {
		// case "DATA":
		// System.out.println("TYPE IS DATA OK!");
		// if (idx.getDsl() != null) {
		// cpidx.CopyIndexByQueryDsl(sourceclient, idx.getSource_index(),
		// targetclient,
		// idx.getTarget_index(), idx.getDsl().toString());
		//
		// } else {
		// cpidx.CopyIndex(sourceclient, idx.getSource_index(), targetclient,
		// idx.getTarget_index());
		// }
		// break;
		// case "META":
		// System.out.println("TYPE IS META OK!");
		// cpidx.CopyIndexMetadata(sourceclient, idx.getSource_index(),
		// targetclient, idx.getTarget_index());
		// break;
		// case "FORCE":
		// System.out.println("FORCE OK!");
		// if (esidxtools.IndexExistes(targetclient, idx.getTarget_index())) {
		// esidxtools.DeleteIndex(targetclient, idx.getTarget_index());
		// }
		//
		// cpidx.CopyIndexMetadata(sourceclient, idx.getSource_index(),
		// targetclient, idx.getTarget_index());
		//
		// if (idx.getDsl() != null) {
		// cpidx.CopyIndexByQueryDsl(sourceclient, idx.getSource_index(),
		// targetclient,
		// idx.getTarget_index(), idx.getDsl().toString());
		//
		// } else {
		// cpidx.CopyIndex(sourceclient, idx.getSource_index(), targetclient,
		// idx.getTarget_index());
		// }
		// break;
		// default:
		// System.out.println("type must be set [data,meta,force]");
		// break;
		// }
		//
		// sourceclient.close();
		// targetclient.close();
		//
		// }
		//
		// System.exit(0);
		// }



		client = new ElasticsearchConnector(argssetting.getCluster(), argssetting.getHost(), argssetting.getPort())
				.getClient();



		client.close();

	}

}
