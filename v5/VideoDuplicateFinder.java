import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class VideoDuplicateFinder extends JFrame {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
        ".mp4", ".avi", ".mkv", ".mov", ".wmv", ".flv", ".webm",
        ".m4v", ".mpg", ".mpeg", ".3gp", ".ts", ".vob", ".ogv"
    );

    private final DefaultListModel<String> folderListModel = new DefaultListModel<>();
    private final JList<String> folderList = new JList<>(folderListModel);
    private final DefaultTableModel tableModel;
    private final JTable resultsTable;
    private final JLabel statusLabel = new JLabel("Add folders and click 'Find Duplicates'");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton scanButton = new JButton("Find Duplicates");
    private final JButton addFolderButton = new JButton("Add Folder");
    private final JButton removeFolderButton = new JButton("Remove Selected");
    private final JCheckBox recurseCheckbox = new JCheckBox("Include subfolders", true);
    private final JRadioButton matchBySize = new JRadioButton("Match by size on disk", true);
    private final JRadioButton matchByLength = new JRadioButton("Match by duration (±1s)");
    private final JRadioButton matchByLengthExact = new JRadioButton("Match by duration (exact)");
    private final JRadioButton matchByName = new JRadioButton("Match by filename (exact)");
    private final JRadioButton matchByNameApprox = new JRadioButton("Match by filename (approximate)");
    private final JTextField ffprobePathField = new JTextField("ffprobe", 18);
    private final JButton browseFfprobeBtn = new JButton("Browse...");

    public VideoDuplicateFinder() {
        super("Video Duplicate Finder");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(950, 650);
        setLocationRelativeTo(null);

        // --- Table setup ---
        String[] columns = {"Delete?", "Group", "File Name", "Duration", "Size (MB)", "Full Path"};
        tableModel = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return c == 0; }
            public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setRowHeight(24);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(55);
        resultsTable.getColumnModel().getColumn(0).setMinWidth(55);
        resultsTable.getColumnModel().getColumn(1).setMaxWidth(60);
        resultsTable.getColumnModel().getColumn(3).setMaxWidth(90);
        resultsTable.getColumnModel().getColumn(4).setMaxWidth(100);
        resultsTable.setAutoCreateRowSorter(true);

        // Alternate row colors per group
        resultsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private final Color COLOR_A = new Color(232, 245, 253);
            private final Color COLOR_B = UIManager.getColor("Table.background");
            public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean foc, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                if (!sel) {
                    Object grp = t.getValueAt(row, 1);
                    int g = grp instanceof Integer ? (int) grp : 0;
                    c.setBackground(g % 2 == 0 ? COLOR_A : COLOR_B);
                }
                return c;
            }
        });

        // --- Folder panel (left) ---
        JPanel folderPanel = new JPanel(new BorderLayout(5, 5));
        folderPanel.setBorder(BorderFactory.createTitledBorder("Selected Folders"));
        folderPanel.setPreferredSize(new Dimension(300, 0));

        JPanel folderButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        folderButtons.add(addFolderButton);
        folderButtons.add(removeFolderButton);

        folderPanel.add(new JScrollPane(folderList), BorderLayout.CENTER);
        folderPanel.add(folderButtons, BorderLayout.SOUTH);

        // --- Controls panel ---
        ButtonGroup matchGroup = new ButtonGroup();
        matchGroup.add(matchBySize);
        matchGroup.add(matchByLength);
        matchGroup.add(matchByLengthExact);
        matchGroup.add(matchByName);
        matchGroup.add(matchByNameApprox);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(recurseCheckbox);
        controlPanel.add(Box.createHorizontalStrut(15));
        controlPanel.add(new JLabel("Match by:"));
        controlPanel.add(matchBySize);
        controlPanel.add(matchByLength);
        controlPanel.add(matchByLengthExact);
        controlPanel.add(matchByName);
        controlPanel.add(matchByNameApprox);
        controlPanel.add(Box.createHorizontalStrut(15));
        controlPanel.add(scanButton);

        // --- FFprobe path panel (shown/hidden based on duration toggle) ---
        JPanel ffprobePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        ffprobePanel.add(new JLabel("FFprobe path:"));
        ffprobePanel.add(ffprobePathField);
        ffprobePanel.add(browseFfprobeBtn);
        ffprobePanel.setVisible(matchByLength.isSelected() || matchByLengthExact.isSelected());

        ActionListener showFfprobe = e -> ffprobePanel.setVisible(true);
        ActionListener hideFfprobe = e -> ffprobePanel.setVisible(false);
        matchByLength.addActionListener(showFfprobe);
        matchByLengthExact.addActionListener(showFfprobe);
        matchBySize.addActionListener(hideFfprobe);
        matchByName.addActionListener(hideFfprobe);
        matchByNameApprox.addActionListener(hideFfprobe);

        browseFfprobeBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                ffprobePathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        JPanel controlWrapper = new JPanel();
        controlWrapper.setLayout(new BoxLayout(controlWrapper, BoxLayout.Y_AXIS));
        controlWrapper.add(controlPanel);
        controlWrapper.add(ffprobePanel);

        // --- Bottom status ---
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        progressBar.setVisible(false);
        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(progressBar, BorderLayout.SOUTH);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        // --- Results panel ---
        JPanel resultsPanel = new JPanel(new BorderLayout(5, 5));
        resultsPanel.setBorder(BorderFactory.createTitledBorder("Duplicate Groups"));
        resultsPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        JButton selectAllBtn = new JButton("Select All");
        JButton deselectAllBtn = new JButton("Deselect All");
        JButton deleteBtn = new JButton("Delete Selected Files");
        deleteBtn.setForeground(Color.RED);

        JPanel resultsButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        resultsButtons.add(selectAllBtn);
        resultsButtons.add(deselectAllBtn);
        resultsButtons.add(Box.createHorizontalStrut(20));
        resultsButtons.add(deleteBtn);
        resultsPanel.add(resultsButtons, BorderLayout.SOUTH);

        selectAllBtn.addActionListener(e -> {
            for (int i = 0; i < tableModel.getRowCount(); i++) tableModel.setValueAt(true, i, 0);
        });
        deselectAllBtn.addActionListener(e -> {
            for (int i = 0; i < tableModel.getRowCount(); i++) tableModel.setValueAt(false, i, 0);
        });
        deleteBtn.addActionListener(e -> deleteSelectedFiles());

        // --- Main layout ---
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(folderPanel, BorderLayout.WEST);
        topPanel.add(controlWrapper, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, resultsPanel);
        split.setDividerLocation(220);
        split.setResizeWeight(0.3);

        add(split, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // --- Actions ---
        addFolderButton.addActionListener(e -> addFolder());
        removeFolderButton.addActionListener(e -> removeFolder());
        scanButton.addActionListener(e -> startScan());

        // Double-click to open the file itself
        resultsTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = resultsTable.getSelectedRow();
                    if (row >= 0) {
                        String path = (String) resultsTable.getValueAt(row, 5);
                        try {
                            Desktop.getDesktop().open(new File(path));
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(null, "Cannot open file: " + ex.getMessage());
                        }
                    }
                }
            }
        });
    }

    private void addFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                String path = f.getAbsolutePath();
                if (!folderListModel.contains(path)) folderListModel.addElement(path);
            }
        }
    }

    private void removeFolder() {
        int idx = folderList.getSelectedIndex();
        if (idx >= 0) folderListModel.remove(idx);
    }

    private void startScan() {
        if (folderListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please add at least one folder.");
            return;
        }
        List<String> folders = Collections.list(folderListModel.elements());
        boolean recurse = recurseCheckbox.isSelected();

        scanButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setVisible(true);
        statusLabel.setText("Scanning...");
        tableModel.setRowCount(0);

        String mode = matchByLength.isSelected() ? "length_approx" :
                      matchByLengthExact.isSelected() ? "length_exact" :
                      matchByNameApprox.isSelected() ? "name_approx" :
                      matchByName.isSelected() ? "name" : "size";
        String ffprobePath = ffprobePathField.getText().trim();

        new SwingWorker<List<List<File>>, String>() {
            protected List<List<File>> doInBackground() {
                // Collect all video files first
                List<File> allFiles = new ArrayList<>();
                for (String folder : folders) {
                    collectFiles(new File(folder), allFiles, recurse);
                }

                if (mode.equals("length_approx")) {
                    return groupByDuration(allFiles, ffprobePath, 1.0);
                } else if (mode.equals("length_exact")) {
                    return groupByDuration(allFiles, ffprobePath, 0.0);
                } else if (mode.equals("name_approx")) {
                    return groupByApproximateName(allFiles);
                } else {
                    return groupByKey(allFiles, mode);
                }
            }

            private void collectFiles(File dir, List<File> out, boolean recurse) {
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File f : files) {
                    if (f.isDirectory() && recurse) {
                        collectFiles(f, out, true);
                    } else if (f.isFile() && isVideo(f.getName())) {
                        out.add(f);
                    }
                }
            }

            private List<List<File>> groupByKey(List<File> files, String mode) {
                Map<String, List<File>> map = new HashMap<>();
                for (File f : files) {
                    publish(f.getName());
                    String key = mode.equals("name") ? f.getName().toLowerCase()
                                                     : String.valueOf(f.length());
                    map.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                }
                map.entrySet().removeIf(e -> e.getValue().size() < 2);
                return new ArrayList<>(map.values());
            }

            private List<List<File>> groupByDuration(List<File> files, String ffprobe, double toleranceSec) {
                // Get durations for all files
                List<double[]> durationsAndIndices = new ArrayList<>();
                List<File> validFiles = new ArrayList<>();
                int total = files.size();
                for (int i = 0; i < total; i++) {
                    File f = files.get(i);
                    publish(String.format("(%d/%d) Probing: %s", i + 1, total, f.getName()));
                    double dur = getVideoDuration(f, ffprobe);
                    if (dur >= 0) {
                        durationsAndIndices.add(new double[]{dur});
                        validFiles.add(f);
                    }
                }

                if (toleranceSec <= 0) {
                    // Exact match: group by rounded-to-second duration
                    Map<Long, List<File>> map = new HashMap<>();
                    for (int i = 0; i < validFiles.size(); i++) {
                        long key = Math.round(durationsAndIndices.get(i)[0]);
                        map.computeIfAbsent(key, k -> new ArrayList<>()).add(validFiles.get(i));
                    }
                    map.entrySet().removeIf(e -> e.getValue().size() < 2);
                    return new ArrayList<>(map.values());
                }

                // Sort by duration for efficient grouping
                int n = validFiles.size();
                Integer[] indices = new Integer[n];
                for (int i = 0; i < n; i++) indices[i] = i;
                Arrays.sort(indices, (a, b) -> Double.compare(
                    durationsAndIndices.get(a)[0], durationsAndIndices.get(b)[0]));

                // Group files within ±tolerance using sorted sweep
                boolean[] used = new boolean[n];
                List<List<File>> groups = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    int si = indices[i];
                    if (used[si]) continue;
                    double baseDur = durationsAndIndices.get(si)[0];
                    List<File> group = new ArrayList<>();
                    group.add(validFiles.get(si));
                    used[si] = true;
                    for (int j = i + 1; j < n; j++) {
                        int sj = indices[j];
                        if (used[sj]) continue;
                        double d = durationsAndIndices.get(sj)[0];
                        if (d - baseDur > toleranceSec) break;
                        group.add(validFiles.get(sj));
                        used[sj] = true;
                    }
                    if (group.size() >= 2) groups.add(group);
                }
                return groups;
            }

            private List<List<File>> groupByApproximateName(List<File> files) {
                // Normalize filename: lowercase, remove extension, remove non-alphanumeric
                List<String> normalized = new ArrayList<>();
                for (File f : files) {
                    publish(f.getName());
                    String name = f.getName();
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) name = name.substring(0, dot);
                    name = name.toLowerCase().replaceAll("[^a-z0-9]", "");
                    normalized.add(name);
                }
                // Group files whose normalized names match
                Map<String, List<File>> map = new HashMap<>();
                for (int i = 0; i < files.size(); i++) {
                    map.computeIfAbsent(normalized.get(i), k -> new ArrayList<>()).add(files.get(i));
                }
                map.entrySet().removeIf(e -> e.getValue().size() < 2);
                return new ArrayList<>(map.values());
            }

            protected void process(List<String> chunks) {
                if (!chunks.isEmpty())
                    statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            protected void done() {
                try {
                    List<List<File>> groups = get();
                    // Sort groups by size of group descending
                    groups.sort((a, b) -> Integer.compare(b.size(), a.size()));
                    int group = 1;
                    int totalDupes = 0;
                    for (List<File> grp : groups) {
                        for (File f : grp) {
                            double mb = f.length() / (1024.0 * 1024.0);
                            String sizeStr = String.format("%.2f", mb);
                            String durStr = formatDuration(getVideoDuration(f, ffprobePath));
                            tableModel.addRow(new Object[]{
                                false, group, f.getName(), durStr, sizeStr, f.getAbsolutePath()
                            });
                            totalDupes++;
                        }
                        group++;
                    }
                    String modeLabel = mode.equals("name") ? "filename" :
                                       mode.equals("length") ? "duration (±1s via ffprobe)" : "exact file size";
                    statusLabel.setText(String.format("Done — %d duplicate groups, %d files (matched by %s).",
                        groups.size(), totalDupes, modeLabel));
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                }
                progressBar.setVisible(false);
                scanButton.setEnabled(true);
            }
        }.execute();
    }

    private void deleteSelectedFiles() {
        List<Integer> rowsToDelete = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) {
                rowsToDelete.add(i);
            }
        }
        if (rowsToDelete.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files selected for deletion.");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
            "Are you sure you want to permanently delete " + rowsToDelete.size() + " file(s)?\nThis cannot be undone.",
            "Confirm Deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        int deleted = 0, failed = 0;
        // Remove rows in reverse order so indices stay valid
        for (int i = rowsToDelete.size() - 1; i >= 0; i--) {
            int row = rowsToDelete.get(i);
            String path = (String) tableModel.getValueAt(row, 5);
            File file = new File(path);
            if (file.delete()) {
                tableModel.removeRow(row);
                deleted++;
            } else {
                failed++;
            }
        }
        String msg = deleted + " file(s) deleted.";
        if (failed > 0) msg += " " + failed + " file(s) could not be deleted.";
        statusLabel.setText(msg);
        JOptionPane.showMessageDialog(this, msg);
    }

    private static boolean isVideo(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return VIDEO_EXTENSIONS.contains(name.substring(dot).toLowerCase());
    }

    /**
     * Formats a duration in seconds to HH:MM:SS or MM:SS.
     * Returns "N/A" if duration is negative.
     */
    private static String formatDuration(double seconds) {
        if (seconds < 0) return "N/A";
        int total = (int) Math.round(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%d:%02d", m, s);
    }

    /**
     * Runs ffprobe to extract the precise duration of a video file in seconds.
     * Returns -1.0 if duration cannot be determined.
     */
    private static double getVideoDuration(File file, String ffprobePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                ffprobePath,
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                file.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            int exitCode = proc.waitFor();
            if (exitCode != 0) return -1.0;

            // Simple JSON parse for "duration" : "123.456"
            String json = sb.toString();
            String marker = "\"duration\"";
            int idx = json.indexOf(marker);
            if (idx < 0) return -1.0;
            int colon = json.indexOf(':', idx + marker.length());
            int quote1 = json.indexOf('"', colon + 1);
            int quote2 = json.indexOf('"', quote1 + 1);
            if (quote1 < 0 || quote2 < 0) return -1.0;
            return Double.parseDouble(json.substring(quote1 + 1, quote2));
        } catch (Exception e) {
            return -1.0;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new VideoDuplicateFinder().setVisible(true);
        });
    }
}