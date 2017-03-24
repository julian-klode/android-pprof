/*
 * Copyright (c) 2017 Julian Andres Klode <jak@jak-linux.org>
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
 */

import com.android.ddmlib.AllocationInfo;
import com.android.ddmlib.AllocationsParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class Main {

    private static final int NODES_TO_SHOW = 42;
    private static int counter = 0;
    private static Map<String, Integer> functionIndex = new HashMap<String, Integer>();
    private static long allocationEdges[];
    private static long totalAlloc;

    public static AllocationInfo[] parse(String allocFilePath) {
        File file = new File(allocFilePath);
        try {
            ByteBuffer buffer;
            try (FileInputStream inputStream = new FileInputStream(file)) {
                FileChannel fileChannel = inputStream.getChannel();
                buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, file.length());
                buffer.order(ByteOrder.BIG_ENDIAN);
            }
            return AllocationsParser.parse(buffer);
        } catch (IOException e) {
            throw new RuntimeException("Could not load " + allocFilePath, e);
        }
    }

    public static String getName(StackTraceElement e) {
        return e.getClassName() + "." + e.getMethodName() + "()";
    }

    private static boolean skip(AllocationInfo info) {
        for (StackTraceElement element : info.getStackTrace()) {
            String name = getName(element);
            if (false)
                return true;
        }
        return false;


    }

    public static void main(String[] args) {
        AllocationInfo[] infos = parse("/home/jak/Projects/Android/DNS66/captures/org.jak_linux.dns66_2017.03.24_02.24.alloc");
        ArrayList<Node> allocationNodes = new ArrayList<>();

        // Build all nodes
        for (AllocationInfo info : infos) {
            totalAlloc += info.getSize();
            if (skip(info))
                continue;

            StackTraceElement[] trace = info.getStackTrace();
            HashSet<String> seen = new HashSet<>();
            for (StackTraceElement element : trace) {
                String name = getName(element);
                Node node;
                if (!functionIndex.containsKey(name)) {
                    functionIndex.put(name, counter++);
                    node = new Node();
                    node.id = counter - 1;
                    node.name = name;
                    allocationNodes.add(node);
                } else {
                    node = allocationNodes.get(functionIndex.get(name));
                }
                if (element == trace[0])
                    node.here = info.getSize();

                if (!seen.contains(name)) {
                    node.total += info.getSize();
                    seen.add(name);
                }
            }
        }

        // Build adjacency matrix
        allocationEdges = new long[functionIndex.size() * functionIndex.size()];
        for (AllocationInfo info : infos) {
            if (skip(info)) {
                continue;
            }
            StackTraceElement[] trace = info.getStackTrace();
            // Visiting from caller to callee
            for (int i = trace.length - 1; i > 1; i--) {
                String nameA = getName(trace[i]);
                int indexA = functionIndex.get(nameA);
                String nameB = getName(trace[i - 1]);
                int indexB = functionIndex.get(nameB);
                allocationEdges[indexA * allocationNodes.size() + indexB] += info.getSize();
            }
        }

        // Render the graph.
        ArrayList<Node> orderedNodes = new ArrayList<>(allocationNodes);
        orderedNodes.sort((a, b) -> Long.compare(b.total, a.total));

        System.out.println("digraph foo {");

        for (int i = 0; i < NODES_TO_SHOW && i < orderedNodes.size(); i++) {
            Node node = orderedNodes.get(i);
            if (node.total > (0.005 * totalAlloc))
                System.out.printf("node [shape=rectangle,height=%f,label=\"%s\\n%d (%f%%) out of %d (%f%%)\"] node%d;\n", node.here / (0.06 * totalAlloc), node.name, node.here, 100.0 * node.here / totalAlloc, node.total, 100.0 * node.total / totalAlloc, node.id);

        }
        for (int i = 0; i < NODES_TO_SHOW && i < orderedNodes.size(); i++) {
            Node node = orderedNodes.get(i);
            for (int j = 0; j < NODES_TO_SHOW && j < orderedNodes.size(); j++) {
                Node nodeB = orderedNodes.get(j);

                long edge = allocationEdges[node.id * allocationNodes.size() + nodeB.id];
                if (edge > 0 && edge > (0.01 * totalAlloc))
                    System.out.printf("node%s -> node%d [label=\"%s (%f%%)\"];\n", node.id, nodeB.id, edge, 100.0 * edge / totalAlloc);
            }
        }

        System.out.println("}");
    }

    static class Node {
        int id;
        String name;
        long total;
        long here;
    }
}
