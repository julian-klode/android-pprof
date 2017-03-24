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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class Main {

    private static final int NODES_TO_SHOW = 42;
    private static int counter = 0;
    private static Map<String, Integer> functionIndex = new HashMap<String, Integer>();
    private static long allocationEdges[];
    private static Node allocationNodes[];
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

        for (AllocationInfo info : infos) {
            totalAlloc += info.getSize();
            if (skip(info))
                continue;
            for (StackTraceElement element : info.getStackTrace()) {
                String name = getName(element);
                if (!functionIndex.containsKey(name)) {
                    functionIndex.put(name, counter++);
                }
            }


        }

        allocationNodes = new Node[functionIndex.size()];
        allocationEdges = new long[functionIndex.size() * functionIndex.size()];

        for (AllocationInfo info : infos) {
            StackTraceElement[] trace = info.getStackTrace();
            List<StackTraceElement> traceList = Arrays.asList(trace);
            Collections.reverse(traceList);
            trace = traceList.toArray(trace);
            if (skip(info)) {
                continue;
            }
            HashSet<String> seen = new HashSet<>();
            for (int i = 0; i < trace.length; i++) {
                String nameA = getName(trace[i]);
                int indexA = functionIndex.get(nameA);
                if (allocationNodes[indexA] == null) {
                    allocationNodes[indexA] = new Node();
                    allocationNodes[indexA].id = indexA;
                    allocationNodes[indexA].name = nameA;
                    allocationNodes[indexA].total = 0;
                    allocationNodes[indexA].here = 0;

                }
                if (!seen.contains(nameA)) {
                    allocationNodes[indexA].total += info.getSize();
                    seen.add(nameA);
                }

                if (i == trace.length - 1) {
                    allocationNodes[indexA].here += info.getSize();
                } else {
                    String nameB = getName(trace[i + 1]);
                    int indexB = functionIndex.get(nameB);
                    allocationEdges[indexA * allocationNodes.length + indexB] += info.getSize();
                }
            }
        }

        Node[] orderedNodes = Arrays.copyOf(allocationNodes, allocationNodes.length);
        Arrays.sort(orderedNodes, new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                return Long.compare(b.total, a.total);
            }
        });

        System.out.println("digraph foo {");

        for (int i = 0; i < NODES_TO_SHOW && i < orderedNodes.length; i++) {
            Node node = orderedNodes[i];
            if (node.total > (0.005 * totalAlloc))
                System.out.printf("node [shape=rectangle,height=%f,label=\"%s\\n%d (%f%%) out of %d (%f%%)\"] node%d;\n", node.here / (0.06 * totalAlloc), node.name, node.here, 100.0 * node.here / totalAlloc, node.total, 100.0 * node.total / totalAlloc, node.id);

        }
        for (int i = 0; i < NODES_TO_SHOW && i < orderedNodes.length; i++) {
            Node node = orderedNodes[i];
            for (int j = 0; j < NODES_TO_SHOW && j < orderedNodes.length; j++) {
                Node nodeB = orderedNodes[j];

                long edge = allocationEdges[node.id * allocationNodes.length + nodeB.id];
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
