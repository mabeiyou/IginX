package cn.edu.tsinghua.iginx.sql.statement;

import cn.edu.tsinghua.iginx.cluster.IginxWorker;
import cn.edu.tsinghua.iginx.engine.shared.RequestContext;
import cn.edu.tsinghua.iginx.engine.shared.Result;
import cn.edu.tsinghua.iginx.exceptions.ExecutionException;
import cn.edu.tsinghua.iginx.thrift.JobState;
import cn.edu.tsinghua.iginx.thrift.QueryTransformJobStatusReq;
import cn.edu.tsinghua.iginx.thrift.QueryTransformJobStatusResp;
import cn.edu.tsinghua.iginx.utils.RpcUtils;

public class ShowJobStatusStatement extends SystemStatement {

    private final long jobId;

    private final IginxWorker worker = IginxWorker.getInstance();

    public ShowJobStatusStatement(long jobId) {
        this.jobId = jobId;
        this.statementType = StatementType.SHOW_JOB_STATUS;
    }

    @Override
    public void execute(RequestContext ctx) throws ExecutionException {
        QueryTransformJobStatusReq req = new QueryTransformJobStatusReq(ctx.getSessionId(), jobId);
        QueryTransformJobStatusResp resp = worker.queryTransformJobStatus(req);
        JobState jobState = resp.getJobState();

        Result result = new Result(RpcUtils.SUCCESS);
        result.setJobState(jobState);
        ctx.setResult(result);
    }
}
