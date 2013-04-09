/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.command;

import java.io.IOException;
import java.util.ArrayList;
import org.h2.constant.SysProperties;
import org.h2.engine.SessionRemote;
import org.h2.expression.ParameterInterface;
import org.h2.expression.ParameterRemote;
import org.h2.message.DbException;
import org.h2.message.Trace;
import org.h2.result.ResultInterface;
import org.h2.result.ResultRemote;
import org.h2.util.New;
import org.h2.value.Transfer;
import org.h2.value.Value;

/**
 * Represents the client-side part of a SQL statement.
 * This class is not used in embedded mode.
 */
public class CommandRemote implements CommandInterface {

    private final ArrayList<Transfer> transferList;
    private final ArrayList<ParameterInterface> parameters;
    private final Trace trace;
    private final String sql;
    private final int fetchSize;
    private SessionRemote session;
    private int id;
    private boolean isQuery;
    private boolean readonly;
    private final int created;

    public CommandRemote(SessionRemote session, ArrayList<Transfer> transferList, String sql, int fetchSize) {
        this.transferList = transferList;
        trace = session.getTrace();
        this.sql = sql;
        parameters = New.arrayList();
        prepare(session, true);
        // set session late because prepare might fail - in this case we don't
        // need to close the object
        this.session = session;
        this.fetchSize = fetchSize;
        created = session.getLastReconnect();
    }

    private void prepare(SessionRemote s, boolean createParams) {
    	//��ȻgetNextId�ڲ���nextId++;
    	//����org.h2.engine.SessionRemote.prepareCommand(String, int)��synchronized�ģ�
    	//
    	//����org.h2.command.CommandRemote.prepareIfRequired()Ҳ��synchronized (session)�е���
    	//��������û�в�������
    	
        id = s.getNextId(); //���id�ᷢ��server�����ڻ���server��Ӧ��command��
        for (int i = 0, count = 0; i < transferList.size(); i++) {
            try {
                Transfer transfer = transferList.get(i);
                if (createParams) {
                    s.traceOperation("SESSION_PREPARE_READ_PARAMS", id);
                    transfer.writeInt(SessionRemote.SESSION_PREPARE_READ_PARAMS).writeInt(id).writeString(sql);
                } else {
                    s.traceOperation("SESSION_PREPARE", id);
                    transfer.writeInt(SessionRemote.SESSION_PREPARE).writeInt(id).writeString(sql);
                }
                s.done(transfer);
                isQuery = transfer.readBoolean();
                readonly = transfer.readBoolean();
                int paramCount = transfer.readInt();
                if (createParams) {
                    parameters.clear();
                    //prepare�׶�ÿ��ParameterRemoteֻ�����ͻ�û��ֵ�����ڽ�����ͨ��getParameters()����JdbcPreparedStatement
                    //Ȼ����JdbcPreparedStatement�����ã������executeQuery��executeUpdate�л�û��Ϊ��Щ��������ֵ��
                    //��ô����checkParametersʱ�����쳣
                    for (int j = 0; j < paramCount; j++) {
                        ParameterRemote p = new ParameterRemote(j);
                        p.readMetaData(transfer);
                        parameters.add(p);
                    }
                }
            } catch (IOException e) {
                s.removeServer(e, i--, ++count);
            }
        }
    }

    public boolean isQuery() {
        return isQuery;
    }

    public ArrayList<ParameterInterface> getParameters() {
        return parameters;
    }

    private void prepareIfRequired() {
        if (session.getLastReconnect() != created) {
            // in this case we need to prepare again in every case
            id = Integer.MIN_VALUE;
        }
        session.checkClosed();
        if (id <= session.getCurrentId() - SysProperties.SERVER_CACHED_OBJECTS) {
            // object is too old - we need to prepare again
            prepare(session, false);
        }
    }
    
    //��������Ƕ�Ӧjava.sql.ResultSetMetaData��������org.h2.jdbc.JdbcPreparedStatement.getMetaData()����
    //�ȼ���ResultSet.getMetaData()��ֻ����PreparedStatement.getMetaData()����Ҫ����ִ�в�ѯ
    public ResultInterface getMetaData() {
        synchronized (session) {
            if (!isQuery) {
                return null;
            }
            int objectId = session.getNextId();
            ResultRemote result = null;
            for (int i = 0, count = 0; i < transferList.size(); i++) {
                prepareIfRequired();
                Transfer transfer = transferList.get(i);
                try {
                    session.traceOperation("COMMAND_GET_META_DATA", id);
                    //����õ���ResultRemoteҲ����server�˰�objectId����һ��org.h2.result.LocalResult
                    //�������ResultRemote��org.h2.jdbc.JdbcPreparedStatement.getMetaData()�б���װ��JdbcResultSetMetaData��
                    //û�л������ResultRemote.close���ͷ�server�˵���ض�����
                    //���Ӱ���ж��?����server�˵�cache�������Ƶģ����Ұ�session�����ã����Բ��������OOM
                    //==========================
                    //����������ˣ�ResultRemote�Ĺ��캯���лᴥ��ResultRemote.fetchRows(boolean)��
                    //��ΪrowCount��0�����Ե��¾͵���sendClose��
                    transfer.writeInt(SessionRemote.COMMAND_GET_META_DATA).writeInt(id).writeInt(objectId);
                    session.done(transfer);
                    int columnCount = transfer.readInt();
                    result = new ResultRemote(session, transfer, objectId, columnCount, Integer.MAX_VALUE);
                    break;
                } catch (IOException e) {
                    session.removeServer(e, i--, ++count);
                }
            }
            session.autoCommitIfCluster();
            return result;
        }
    }

    public ResultInterface executeQuery(int maxRows, boolean scrollable) {
        checkParameters();
        synchronized (session) {
            int objectId = session.getNextId();
            ResultRemote result = null;
            for (int i = 0, count = 0; i < transferList.size(); i++) {
                prepareIfRequired();
                Transfer transfer = transferList.get(i);
                try {
                    session.traceOperation("COMMAND_EXECUTE_QUERY", id);
                    transfer.writeInt(SessionRemote.COMMAND_EXECUTE_QUERY).writeInt(id).writeInt(objectId).writeInt(
                            maxRows);
                    int fetch;
                    if (session.isClustered() || scrollable) {
                        fetch = Integer.MAX_VALUE;
                    } else {
                        fetch = fetchSize;
                    }
                    transfer.writeInt(fetch);
                    //�����JdbcStatement��û�в�����JdbcPreparedStatement����
                    sendParameters(transfer);
                    session.done(transfer);
                    int columnCount = transfer.readInt();
                    if (result != null) {
                        result.close();
                        result = null;
                    }
                    result = new ResultRemote(session, transfer, objectId, columnCount, fetch);
                    //����ֻ����ѯֻ��Ҫ��һ̨server�ϻ�ý���Ϳ����ˣ�����Ҫÿ̨server����ѯһ��
                    //select ... for update������ʹreadonlyΪfalse��
                    //�෴����where�м���һЩNotDeterministic�ĺ���������RAND��RANDOM�ȷ�����ʹreadonlyΪfalse
                    //��org.h2.expression.Function.addFunctionNotDeterministic(String, int, int, int)
                    //����: select * from SessionRemoteTest where id>? and b=? and id<RAND()
                    if (readonly) {
                        break;
                    }
                } catch (IOException e) {
                    session.removeServer(e, i--, ++count);
                }
            }
            session.autoCommitIfCluster();
            session.readSessionState();
            return result;
        }
    }
    
    //ע��: transferList.size����1ʱ��˵���Ǽ�Ⱥ���������ǲ�����XA��Ҳ����˵������һ̨server���³ɹ��ˣ�����������һ̨���²��ɹ�
    public int executeUpdate() {
        checkParameters();
        synchronized (session) {
            int updateCount = 0;
            boolean autoCommit = false;
            for (int i = 0, count = 0; i < transferList.size(); i++) {
                prepareIfRequired();
                Transfer transfer = transferList.get(i);
                try {
                    session.traceOperation("COMMAND_EXECUTE_UPDATE", id);
                    transfer.writeInt(SessionRemote.COMMAND_EXECUTE_UPDATE).writeInt(id);
                    //�����JdbcStatement��û�в�����JdbcPreparedStatement����
                    sendParameters(transfer);
                    session.done(transfer);
                    updateCount = transfer.readInt();
                    autoCommit = transfer.readBoolean();
                } catch (IOException e) {
                	//ֻ������server������ʱ�ų�������(����Ҫȡ���ڸ��ֲ���)
                    session.removeServer(e, i--, ++count);
                }
            }
            session.setAutoCommitFromServer(autoCommit); //����Ǽ�Ⱥ��������Ϊfalse
            session.autoCommitIfCluster(); //����Ǽ�Ⱥ������֪ͨ����server�ύ����
            session.readSessionState();//��session״̬�����ı�ʱ����ȡINFORMATION_SCHEMA.SESSION_STATE��Ϣ���´ο��ؽ�session
            return updateCount;
        }
    }

    private void checkParameters() {
        for (ParameterInterface p : parameters) {
            p.checkSet();
        }
    }

    private void sendParameters(Transfer transfer) throws IOException {
        int len = parameters.size();
        transfer.writeInt(len);
        for (ParameterInterface p : parameters) {
            transfer.writeValue(p.getParamValue());
        }
    }

    public void close() {
        if (session == null || session.isClosed()) {
            return;
        }
        synchronized (session) {
            session.traceOperation("COMMAND_CLOSE", id);
            for (Transfer transfer : transferList) {
                try {
                    transfer.writeInt(SessionRemote.COMMAND_CLOSE).writeInt(id);
                } catch (IOException e) {
                    trace.error(e, "close");
                }
            }
        }
        session = null;
        try {
            for (ParameterInterface p : parameters) {
                Value v = p.getParamValue();
                if (v != null) {
                    v.close();
                }
            }
        } catch (DbException e) {
            trace.error(e, "close");
        }
        parameters.clear();
    }

    /**
     * Cancel this current statement.
     */
    public void cancel() {
        session.cancelStatement(id);
    }

    public String toString() {
        return sql + Trace.formatParams(getParameters());
    }

    public int getCommandType() {
        return UNKNOWN;
    }

}