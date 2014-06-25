
package edu.oregonstate.cartography.gui;

import edu.oregonstate.cartography.grid.Model;

/**
 *
 * @author Bernhard Jenny, Cartography and Geovisualization Group, Oregon State
 * University
 */
public class SettingsDialog extends javax.swing.JDialog {

    /**
     * Creates new form SettingsDialog
     * @param parent
     * @param modal
     */
    public SettingsDialog(java.awt.Frame parent, boolean modal) {
        super(parent, modal);
        initComponents();
        pack();
        // dispatch menu keyboard events to owner window
        DialogUtil.registerJDialog(this);
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        settingsPanel = new edu.oregonstate.cartography.gui.SettingsPanel();

        setTitle("Pyramid Shader Settings");
        setResizable(false);
        setType(java.awt.Window.Type.UTILITY);
        getContentPane().setLayout(new java.awt.FlowLayout());
        getContentPane().add(settingsPanel);

        pack();
    }// </editor-fold>//GEN-END:initComponents

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private edu.oregonstate.cartography.gui.SettingsPanel settingsPanel;
    // End of variables declaration//GEN-END:variables

    void setModel(Model model) {
        settingsPanel.setModel(model);
    }
}