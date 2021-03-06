/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */
import * as React from 'react';
import { Provider } from 'constate';
import CheckpointView from './CheckpointView';

import CPContainer, { initialState } from './CheckpointViewContainer';
import CheckpointErrorBoundary from './CheckpointErrorBoundry';

interface Props {
    orgId: string;
    projectId: string;
}

const dev = process.env.NODE_ENV !== 'production';

export default ({ orgId, projectId }: Props) => {
    return (
        <Provider devtools={dev}>
            <CPContainer initialState={{ ...initialState, orgId, projectId }}>
                {({
                    checkpointGroups,
                    refreshProcessData,
                    limitPerPage,
                    currentPage,
                    processes
                }) => (
                    <CheckpointErrorBoundary>
                        <CheckpointView
                            processes={processes}
                            checkpointGroups={checkpointGroups}
                            pollDataFn={() =>
                                refreshProcessData({
                                    orgId,
                                    projectId,
                                    limit: limitPerPage,
                                    offset: (currentPage - 1) * limitPerPage
                                })
                            }
                            pollInterval={5000}
                        />
                    </CheckpointErrorBoundary>
                )}
            </CPContainer>
        </Provider>
    );
};
