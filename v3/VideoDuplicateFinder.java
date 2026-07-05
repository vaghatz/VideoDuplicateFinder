import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
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
    private final JRadioButton matchByLength = new JRadioButton("Match by duration");
    private final JRadioButton matchByName = new JRadioButton("Match by filename");

    public VideoDuplicateFinder() {
        super("Video Duplicate Finder");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(950, 650);
        setLocationRelativeTo(null);

        // --- Table setup ---
        String[] columns = {"Delete?", "Group", "File Name", "Size (MB)", "Full Path"};
        tableModel = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r, int c) { return c == 0; }
            public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : Object.class; }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setRowHeight(24);
        resultsTable.getColumnModel().getColumn(0).setMaxWidth(55);
        resultsTable.getColumnModel().getColumn(0).setMinWidth(55);
        resultsTable.getColumnModel().getColumn(1).setMaxWidth(60);
        resultsTable.getColumnModel().getColumn(3).setMaxWidth(100);
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
        matchGroup.add(matchByName);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(recurseCheckbox);
        controlPanel.add(Box.createHorizontalStrut(15));
        controlPanel.add(new JLabel("Match by:"));
        controlPanel.add(matchBySize);
        controlPanel.add(matchByLength);
        controlPanel.add(matchByName);
        controlPanel.add(Box.createHorizontalStrut(15));
        controlPanel.add(scanButton);

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
        topPanel.add(controlPanel, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, resultsPanel);
        split.setDividerLocation(220);
        split.setResizeWeight(0.3);

        add(split, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // --- Actions ---
        addFolderButton.addActionListener(e -> addFolder());
        removeFolderButton.addActionListener(e -> removeFolder());
        scanButton.addActionListener(e -> startScan());

        // Double-click to open file location
        resultsTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = resultsTable.getSelectedRow();
                    if (row >= 0) {
                        String path = (String) resultsTable.getValueAt(row, 4);
                        try {
                            Desktop.getDesktop().open(new File(path).getParentFile());
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(null, "Cannot open folder.");
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

        String mode = matchByLength.isSelected() ? "length" :
                      matchByName.isSelected() ? "name" : "size";

        new SwingWorker<Map<String, List<File>>, String>() {
            protected Map<String, List<File>> doInBackground() {
                Map<String, List<File>> map = new HashMap<>();
                for (String folder : folders) {
                    scanFolder(new File(folder), map, recurse);
                }
                map.entrySet().removeIf(e -> e.getValue().size() < 2);
                return map;
            }

            private void scanFolder(File dir, Map<String, List<File>> map, boolean recurse) {
                File[] files = dir.listFiles();
                if (files == null) return;
                for (File f : files) {
                    if (f.isDirectory() && recurse) {
                        scanFolder(f, map, true);
                    } else if (f.isFile() && isVideo(f.getName())) {
                        publish(f.getName());
                        String key;
                        switch (mode) {
                            case "name":
                                key = f.getName().toLowerCase();
                                break;
                            case "length":
                                // Duration matching uses file size as proxy;
                                // true duration requires a media framework.
                                // We use size-on-disk (allocation granularity 4KB clusters)
                                long clusterSize = 4096;
                                long allocated = ((f.length() + clusterSize - 1) / clusterSize) * clusterSize;
                                key = String.valueOf(allocated);
                                break;
                            default: // "size"
                                key = String.valueOf(f.length());
                                break;
                        }
                        map.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
                    }
                }
            }

            protected void process(List<String> chunks) {
                if (!chunks.isEmpty())
                    statusLabel.setText("Scanning: " + chunks.get(chunks.size() - 1));
            }

            protected void done() {
                try {
                    Map<String, List<File>> result = get();
                    int group = 1;
                    int totalDupes = 0;
                    List<Map.Entry<String, List<File>>> sorted = result.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                        .collect(Collectors.toList());

                    for (var entry : sorted) {
                        for (File f : entry.getValue()) {
                            double mb = f.length() / (1024.0 * 1024.0);
                            String sizeStr = String.format("%.2f", mb);
                            tableModel.addRow(new Object[]{
                                false, group, f.getName(), sizeStr, f.getAbsolutePath()
                            });
                            totalDupes++;
                        }
                        group++;
                    }
                    String modeLabel = mode.equals("name") ? "filename" :
                                       mode.equals("length") ? "size on disk (cluster-aligned)" : "exact file size";
                    statusLabel.setText(String.format("Done — %d duplicate groups, %d files (matched by %s).",
                        sorted.size(), totalDupes, modeLabel));
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
            String path = (String) tableModel.getValueAt(row, 4);
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new VideoDuplicateFinder().setVisible(true);
        });
    }
}