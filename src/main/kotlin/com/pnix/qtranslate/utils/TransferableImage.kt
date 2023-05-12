package com.pnix.qtranslate.utils


import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage


class ImageTransferable(private val image: BufferedImage) : Transferable {
  override fun getTransferData(flavor: DataFlavor?): Any {
    return if (flavor == DataFlavor.imageFlavor) {
      image
    } else {
      throw UnsupportedFlavorException(flavor)
    }
  }

  override fun getTransferDataFlavors(): Array<DataFlavor> {
    return arrayOf(DataFlavor.imageFlavor)
  }

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean {
    return flavor == DataFlavor.imageFlavor
  }
}