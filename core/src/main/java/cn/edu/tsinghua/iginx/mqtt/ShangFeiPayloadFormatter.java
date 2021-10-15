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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package cn.edu.tsinghua.iginx.mqtt;

import cn.edu.tsinghua.iginx.thrift.DataType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ShangFeiPayloadFormatter implements IPayloadFormatter {

    private static final Gson gson = new GsonBuilder().create();

    private static final Logger logger = LoggerFactory.getLogger(ShangFeiPayloadFormatter.class);

    @Override
    public List<Message> format(ByteBuf payload) {
        if (payload == null) {
            return null;
        }
        String txt = payload.toString(StandardCharsets.UTF_8);
        JsonArray jsonArray = gson.fromJson(txt, JsonArray.class);
        List<Message> messages = new ArrayList<>();

        for (int i = 0; i < jsonArray.size(); i++) {
            JsonObject jsonObject = jsonArray.get(i).getAsJsonObject();
            JsonObject metadata = jsonObject.get("metadata").getAsJsonObject();
            JsonElement value = jsonObject.get("value");

            String path = metadata.get("path").getAsString().replace('/', '.').substring(1) + "." + jsonObject.get("name").getAsString();
            long timestamp = jsonObject.get("timestamp").getAsLong();

            Message message = new Message();
            message.setPath(path);
            message.setTimestamp(timestamp);

            String dataType = metadata.get("dataType").getAsString().toLowerCase(Locale.ROOT);
            switch (dataType) {
                case "boolean":
                    message.setValue(value.getAsBoolean());
                    message.setDataType(DataType.BOOLEAN);
                    break;
                case "char":
                case "string":
                    message.setValue(value.getAsString().getBytes(StandardCharsets.UTF_8));
                    message.setDataType(DataType.BINARY);
                    break;
                case "byte":
                    message.setValue(value.getAsInt());
                    message.setDataType(DataType.INTEGER);
                    break;
                case "short":
                    message.setValue((int)value.getAsShort());
                    message.setDataType(DataType.INTEGER);
                    break;
                case "float":
                    message.setValue(value.getAsFloat());
                    message.setDataType(DataType.FLOAT);
                    break;
                default:
                    logger.warn("unknown datatype of mqtt: " + dataType + ", process as string.");
                    message.setValue(value.getAsString().getBytes(StandardCharsets.UTF_8));
                    message.setDataType(DataType.BINARY);
                    break;
            }
            messages.add(message);
        }
        return messages;
    }
}