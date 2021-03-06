/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import org.apache.cassandra.auth.AuthenticatedUser;
import org.apache.cassandra.cql3.CFName;
import org.apache.cassandra.cql3.CQLStatement;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.Event;
import org.apache.cassandra.transport.messages.ResultMessage;

/**
 * Abstract class for statements that alter the schema.
 */
//对应drop/create keyspace/type/table/index/trigger
//以及alter keyspace/type/table
//index/trigger没有alter

//通常由QueryProcessor.process(String, ConsistencyLevel)方法开始
//调用顺序setBoundVariables->prepareKeyspace->prepare->getBoundTerms->checkAccess->validate->execute
public abstract class SchemaAlteringStatement extends CFStatement implements CQLStatement
{
    private final boolean isColumnFamilyLevel;

    protected SchemaAlteringStatement() //对应alter/drop/create keyspace或type，不需要列族
    {
        super(null);
        this.isColumnFamilyLevel = false;
    }

    protected SchemaAlteringStatement(CFName name)
    {
        super(name);
        this.isColumnFamilyLevel = true;
    }

    public int getBoundTerms() //所有与Schema相关的DDL语句都不允许使用?号占位符
    {
        return 0;
    }

    @Override
    public void prepareKeyspace(ClientState state) throws InvalidRequestException
    {
        if (isColumnFamilyLevel)
            super.prepareKeyspace(state);
    }

    @Override
    public Prepared prepare()
    {
        return new Prepared(this);
    }

    public abstract Event.SchemaChange changeEvent();

    /**
     * Schema alteration may result in a new database object (keyspace, table, role, function) being created capable of
     * having permissions GRANTed on it. The creator of the object (the primary role assigned to the AuthenticatedUser
     * performing the operation) is automatically granted ALL applicable permissions on the object. This is a hook for
     * subclasses to override in order to perform that grant when the statement is executed.
     */
    protected void grantPermissionsToCreator(QueryState state)
    {
        // no-op by default
    }

    /**
     * Announces the migration to other nodes in the cluster.
     * @return true if the execution of this statement resulted in a schema change, false otherwise (when IF NOT EXISTS
     * is used, for example)
     * @throws RequestValidationException
     */
    public abstract boolean announceMigration(boolean isLocalOnly) throws RequestValidationException;

    //只有DropIndexStatement子类覆盖了此方法
    public ResultMessage execute(QueryState state, QueryOptions options) throws RequestValidationException
    {
        // If an IF [NOT] EXISTS clause was used, this may not result in an actual schema change.  To avoid doing
        // extra work in the drivers to handle schema changes, we return an empty message in this case. (CASSANDRA-7600)
        boolean didChangeSchema = announceMigration(false);
        if (!didChangeSchema)
            return new ResultMessage.Void();

        Event.SchemaChange ce = changeEvent();

        // when a schema alteration results in a new db object being created, we grant permissions on the new
        // object to the user performing the request if:
        // * the user is not anonymous
        // * the configured IAuthorizer supports granting of permissions (not all do, AllowAllAuthorizer doesn't and
        //   custom external implementations may not)
        AuthenticatedUser user = state.getClientState().getUser();
        if (user != null && !user.isAnonymous() && ce != null && ce.change == Event.SchemaChange.Change.CREATED)
        {
            try
            {
                grantPermissionsToCreator(state);
            }
            catch (UnsupportedOperationException e)
            {
                // not a problem, grant is an optional method on IAuthorizer
            }
        }

        return ce == null ? new ResultMessage.Void() : new ResultMessage.SchemaChange(ce);
    }

    public ResultMessage executeInternal(QueryState state, QueryOptions options) //没有子类覆盖此方法
    {
        boolean didChangeSchema = announceMigration(true);
        if (!didChangeSchema)
            return new ResultMessage.Void();

        Event.SchemaChange ce = changeEvent();
        return ce == null ? new ResultMessage.Void() : new ResultMessage.SchemaChange(ce);
    }
}
